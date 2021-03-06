/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.runtime.webmonitor.history;

import io.netty.handler.codec.http.router.Router;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.GlobalConfiguration;
import org.apache.flink.configuration.HistoryServerOptions;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.core.fs.Path;
import org.apache.flink.runtime.history.FsJobArchivist;
import org.apache.flink.runtime.net.SSLUtils;
import org.apache.flink.runtime.security.SecurityUtils;
import org.apache.flink.runtime.webmonitor.WebMonitorUtils;
import org.apache.flink.runtime.webmonitor.handlers.DashboardConfigHandler;
import org.apache.flink.runtime.webmonitor.utils.WebFrontendBootstrap;
import org.apache.flink.util.FileUtils;
import org.apache.flink.util.FlinkException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The HistoryServer provides a WebInterface and REST API to retrieve information about finished jobs for which
 * the JobManager may have already shut down.
 * 
 * The HistoryServer regularly checks a set of directories for job archives created by the {@link FsJobArchivist} and
 * caches these in a local directory. See {@link HistoryServerArchiveFetcher}.
 * 
 * All configuration options are defined in{@link HistoryServerOptions}.
 * 
 * The WebInterface only displays the "Completed Jobs" page.
 * 
 * The REST API is limited to
 * <ul>
 *     <li>/config</li>
 *     <li>/joboverview</li>
 *     <li>/jobs/:jobid/*</li>
 * </ul>
 * and relies on static files that are served by the {@link HistoryServerStaticFileServerHandler}.
 */
public class HistoryServer {

	private static final Logger LOG = LoggerFactory.getLogger(HistoryServer.class);

	private final Configuration config;

	private final String webAddress;
	private final int webPort;
	private final long webRefreshIntervalMillis;
	private final File webDir;

	private final HistoryServerArchiveFetcher archiveFetcher;

	private final SSLContext serverSSLContext;
	private WebFrontendBootstrap netty;

	private final Object startupShutdownLock = new Object();
	private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
	private final Thread shutdownHook;

	public static void main(String[] args) throws Exception {
		ParameterTool pt = ParameterTool.fromArgs(args);
		String configDir = pt.getRequired("configDir");

		LOG.info("Loading configuration from {}", configDir);
		final Configuration flinkConfig = GlobalConfiguration.loadConfiguration(configDir);

		// run the history server
		SecurityUtils.install(new SecurityUtils.SecurityConfiguration(flinkConfig));

		try {
			SecurityUtils.getInstalledContext().runSecured(new Callable<Integer>() {
				@Override
				public Integer call() throws Exception {
					HistoryServer hs = new HistoryServer(flinkConfig);
					hs.run();
					return 0;
				}
			});
			System.exit(0);
		} catch (UndeclaredThrowableException ute) {
			Throwable cause = ute. getUndeclaredThrowable();
			LOG.error("Failed to run HistoryServer.", cause);
			cause.printStackTrace();
			System.exit(1);
		} catch (Exception e) {
			LOG.error("Failed to run HistoryServer.", e);
			e.printStackTrace();
			System.exit(1);
		}
	}

	public HistoryServer(Configuration config) throws IOException, FlinkException {
		this.config = config;
		if (config.getBoolean(HistoryServerOptions.HISTORY_SERVER_WEB_SSL_ENABLED) && SSLUtils.getSSLEnabled(config)) {
			LOG.info("Enabling SSL for the history server.");
			try {
				this.serverSSLContext = SSLUtils.createSSLServerContext(config);
			} catch (Exception e) {
				throw new IOException("Failed to initialize SSLContext for the history server.", e);
			}
		} else {
			this.serverSSLContext = null;
		}

		webAddress = config.getString(HistoryServerOptions.HISTORY_SERVER_WEB_ADDRESS);
		webPort = config.getInteger(HistoryServerOptions.HISTORY_SERVER_WEB_PORT);
		webRefreshIntervalMillis = config.getLong(HistoryServerOptions.HISTORY_SERVER_WEB_REFRESH_INTERVAL);

		String webDirectory = config.getString(HistoryServerOptions.HISTORY_SERVER_WEB_DIR);
		if (webDirectory == null) {
			webDirectory = System.getProperty("java.io.tmpdir") + "flink-web-history-" + UUID.randomUUID();
		}
		webDir = new File(webDirectory);

		String refreshDirectories = config.getString(HistoryServerOptions.HISTORY_SERVER_ARCHIVE_DIRS);
		if (refreshDirectories == null) {
			throw new FlinkException(HistoryServerOptions.HISTORY_SERVER_ARCHIVE_DIRS + " was not configured.");
		}
		List<RefreshLocation> refreshDirs = new ArrayList<>();
		for (String refreshDirectory : refreshDirectories.split(",")) {
			try {
				Path refreshPath = WebMonitorUtils.validateAndNormalizeUri(new Path(refreshDirectory).toUri());
				FileSystem refreshFS = refreshPath.getFileSystem();
				refreshDirs.add(new RefreshLocation(refreshPath, refreshFS));
			} catch (Exception e) {
				// there's most likely something wrong with the path itself, so we ignore it from here on
				LOG.warn("Failed to create Path or FileSystem for directory '{}'. Directory will not be monitored.", refreshDirectory, e);
			}
		}

		if (refreshDirs.isEmpty()) {
			throw new FlinkException("Failed to validate any of the configured directories to monitor.");
		}

		long refreshIntervalMillis = config.getLong(HistoryServerOptions.HISTORY_SERVER_ARCHIVE_REFRESH_INTERVAL);
		archiveFetcher = new HistoryServerArchiveFetcher(refreshIntervalMillis, refreshDirs, webDir);

		this.shutdownHook = new Thread() {
			@Override
			public void run() {
				HistoryServer.this.stop();
			}
		};
		// add shutdown hook for deleting the directories and remaining temp files on shutdown
		try {
			Runtime.getRuntime().addShutdownHook(shutdownHook);
		} catch (IllegalStateException e) {
			// race, JVM is in shutdown already, we can safely ignore this
			LOG.debug("Unable to add shutdown hook, shutdown already in progress", e);
		} catch (Throwable t) {
			// these errors usually happen when the shutdown is already in progress
			LOG.warn("Error while adding shutdown hook", t);
		}
	}

	public void run() {
		try {
			start();
			new CountDownLatch(1).await();
		} catch (Exception e) {
			LOG.error("Failure while running HistoryServer.", e);
		} finally {
			stop();
		}
	}

	// ------------------------------------------------------------------------
	// Life-cycle
	// ------------------------------------------------------------------------

	void start() throws IOException, InterruptedException {
		synchronized (startupShutdownLock) {
			LOG.info("Starting history server.");

			Files.createDirectories(webDir.toPath());
			LOG.info("Using directory {} as local cache.", webDir);

			Router router = new Router();
			router.GET("/:*", new HistoryServerStaticFileServerHandler(webDir));

			if (!webDir.exists() && !webDir.mkdirs()) {
				throw new IOException("Failed to create local directory " + webDir.getAbsoluteFile() + ".");
			}

			createDashboardConfigFile();

			archiveFetcher.start();

			netty = new WebFrontendBootstrap(router, LOG, webDir, serverSSLContext, webAddress, webPort, config);
		}
	}

	void stop() {
		if (shutdownRequested.compareAndSet(false, true)) {
			synchronized (startupShutdownLock) {
				LOG.info("Stopping history server.");

				try {
					netty.shutdown();
				} catch (Throwable t) {
					LOG.warn("Error while shutting down WebFrontendBootstrap.", t);
				}

				archiveFetcher.stop();

				try {
					LOG.info("Removing web dashboard root cache directory {}", webDir);
					FileUtils.deleteDirectory(webDir);
				} catch (Throwable t) {
					LOG.warn("Error while deleting web root directory {}", webDir, t);
				}

				LOG.info("Stopped history server.");

				// Remove shutdown hook to prevent resource leaks, unless this is invoked by the shutdown hook itself
				if (shutdownHook != null && shutdownHook != Thread.currentThread()) {
					try {
						Runtime.getRuntime().removeShutdownHook(shutdownHook);
					} catch (IllegalStateException ignored) {
						// race, JVM is in shutdown already, we can safely ignore this
					} catch (Throwable t) {
						LOG.warn("Exception while unregistering HistoryServer cleanup shutdown hook.");
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------------
	// File generation
	// ------------------------------------------------------------------------

	static FileWriter createOrGetFile(File folder, String name) throws IOException {
		File file = new File(folder, name + ".json");
		if (!file.exists()) {
			Files.createFile(file.toPath());
		}
		FileWriter fr = new FileWriter(file);
		return fr;
	}

	private void createDashboardConfigFile() throws IOException {
		try (FileWriter fw = createOrGetFile(webDir, "config")) {
			fw.write(DashboardConfigHandler.createConfigJson(webRefreshIntervalMillis));
			fw.flush();
		} catch (IOException ioe) {
			LOG.error("Failed to write config file.");
			throw ioe;
		}
	}

	/**
	 * Container for the {@link Path} and {@link FileSystem} of a refresh directory.
	 */
	static class RefreshLocation {
		private final Path path;
		private final FileSystem fs;

		private RefreshLocation(Path path, FileSystem fs) {
			this.path = path;
			this.fs = fs;
		}

		public Path getPath() {
			return path;
		}

		public FileSystem getFs() {
			return fs;
		}
	}
}
