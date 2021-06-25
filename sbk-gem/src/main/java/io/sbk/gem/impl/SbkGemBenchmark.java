/**
 * Copyright (c) KMG. All Rights Reserved..
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.sbk.gem.impl;

import io.sbk.api.Benchmark;
import io.sbk.gem.GemConfig;
import io.sbk.gem.GemParameters;
import io.sbk.gem.SshConnection;
import io.sbk.gem.SshResponse;
import io.sbk.perl.State;
import io.sbk.system.Printer;
import lombok.Synchronized;
import org.apache.commons.lang.StringUtils;

import javax.annotation.concurrent.GuardedBy;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SbkGemBenchmark implements Benchmark {
    private final Benchmark ramBenchmark;
    private final GemConfig config;
    private final GemParameters params;
    private final String sbkArgs;
    private final CompletableFuture<Void> retFuture;
    private final ExecutorService executor;
    private final SbkSsh[] nodes;

    @GuardedBy("this")
    private State state;

    public SbkGemBenchmark(Benchmark ramBenchmark, GemConfig config, GemParameters params, String sbkArgs) {
        this.ramBenchmark = ramBenchmark;
        this.config = config;
        this.config.remoteTimeoutSeconds = Long.MAX_VALUE;
        this.params = params;
        this.sbkArgs = sbkArgs;
        this.retFuture =  new CompletableFuture<>();
        this.state = State.BEGIN;
        final SshConnection[] conns = params.getConnections();
        if (config.fork) {
            executor = new ForkJoinPool(conns.length + 10);
        } else {
            executor = Executors.newFixedThreadPool(conns.length + 10);
        }
        this.nodes = new SbkSsh[conns.length];
        for (int i = 0; i < conns.length; i++) {
            nodes[i] =  new SbkSsh(conns[i],  executor);
        }
    }


    @Override
    @Synchronized
    public CompletableFuture<Void> start() throws IOException, InterruptedException, ExecutionException, IllegalStateException {
        if (state != State.BEGIN) {
            if (state == State.RUN) {
                Printer.log.warn("SBK GEM Benchmark is already running..");
            } else {
                Printer.log.warn("SBK GEM Benchmark is already shutdown..");
            }
            return retFuture;
        }
        state = State.RUN;
        Printer.log.info("SBK GEM Benchmark Started");
        final CompletableFuture[] cfArray = new CompletableFuture[nodes.length];

        for (int i = 0; i < nodes.length; i++) {
            cfArray[i] = nodes[i].createSessionAsync(config.remoteTimeoutSeconds);
        }
        final CompletableFuture<Void> connsFuture = CompletableFuture.allOf(cfArray);

        for (int i = 0; i < config.maxIterations && !connsFuture.isDone(); i++) {
            try {
                connsFuture.get(config.timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException ex) {
                Printer.log.info("SBK-GEM [" + (i + 1) + "]: Waiting for ssh session to remote hosts timeout");
            }
        }
        if (!connsFuture.isDone() || connsFuture.isCompletedExceptionally()) {
            final String errMsg = "SBK-GEM, remote session failed after " + config.maxIterations + " iterations";
            Printer.log.error(errMsg);
            throw new InterruptedException(errMsg);
        }
        Printer.log.info("SBK-GEM , ssh session establishment complete..");

        final int  javaMajorVersion = Integer.parseInt(System.getProperty("java.runtime.version").
                split("\\.")[0]);
        boolean stop = false;

        final SshResponse[] sshResults = createMultiSshResponse(nodes.length, true);
        final String cmd = "java -version";
        for (int i = 0; i < nodes.length; i++) {
            cfArray[i] = nodes[i].runCommandAsync(cmd, config.remoteTimeoutSeconds, sshResults[i]);
        }
        final CompletableFuture<Void> ret = CompletableFuture.allOf(cfArray);

        for (int i = 0; i < config.maxIterations && !ret.isDone(); i++) {
            try {
                ret.get(config.timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException ex) {
                Printer.log.info("SBK-GEM [" + (i + 1) + "]: Waiting for command: " + cmd + " timeout");
            }
        }

        if (!ret.isDone()) {
            final String errMsg = "SBK-GEM, command: " + cmd +" time out after " + config.maxIterations + " iterations";
            Printer.log.error(errMsg);
            throw new InterruptedException(errMsg);
        } else {
            for (int i = 0; i < sshResults.length; i++) {
                String stdOut = sshResults[i].stdOutput.toString();
                String stdErr = sshResults[i].errOutput.toString();
                if (javaMajorVersion > parseJavaVersion(stdOut) && javaMajorVersion > parseJavaVersion(stdErr)) {
                    Printer.log.info("Java version :" + javaMajorVersion+" , mismatch at : "+ nodes[i].connection.getHost());
                    stop = true;
                }
            }
        }

        if (stop) {
            throw new InterruptedException();
        }
        Printer.log.info("Java version match Success..");

        final SshResponse[] results = createMultiSshResponse(nodes.length, false);
        for (int i = 0; i < nodes.length; i++) {
            cfArray[i] = nodes[i].runCommandAsync("rm -rf " + nodes[i].connection.getDir(),
                    config.remoteTimeoutSeconds, results[i]);
        }
        final CompletableFuture<Void> rmFuture = CompletableFuture.allOf(cfArray);

        for (int i = 0; i < config.maxIterations && !rmFuture.isDone(); i++) {
            try {
                rmFuture.get(config.timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException ex) {
                Printer.log.info("SBK-GEM [" + (i + 1) + "]: Waiting for command: " + cmd + " timeout");
            }
        }

        if (!rmFuture.isDone()) {
            final String errMsg = "SBK-GEM, command:  'rm -rf' time out after " + config.maxIterations + " iterations";
            Printer.log.error(errMsg);
            throw new InterruptedException(errMsg);
        }

        final SshResponse[] mkDirResults = createMultiSshResponse(nodes.length, false);

        for (int i = 0; i < nodes.length; i++) {
            cfArray[i] = nodes[i].runCommandAsync("mkdir -p " + nodes[i].connection.getDir(),
                    config.remoteTimeoutSeconds, mkDirResults[i]);
        }

        final CompletableFuture<Void> mkDirFuture = CompletableFuture.allOf(cfArray);

        for (int i = 0; !mkDirFuture.isDone(); i++) {
            try {
                mkDirFuture.get(config.timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException ex) {
                Printer.log.info("SBK-GEM [" + (i + 1) + "]: Waiting for command: " + cmd + " timeout");
            }
        }

        if (!mkDirFuture.isDone()) {
            final String errMsg = "SBK-GEM, command:  'mkdir' time out after " + config.maxIterations + " iterations";
            Printer.log.error(errMsg);
            throw new InterruptedException(errMsg);
        }

        for (int i = 0; i < nodes.length; i++) {
            cfArray[i] = nodes[i].copyDirectoryAsync(params.getSbkDir(), nodes[i].connection.getDir());
        }
        final CompletableFuture<Void> copyCB = CompletableFuture.allOf(cfArray);

        for (int i = 0; !copyCB.isDone(); i++) {
            try {
                copyCB.get(config.timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException ex) {
                Printer.log.info("SBK-GEM [" + (i + 1) + "]: Waiting for copy command to complete");
            }
        }

        if (copyCB.isCompletedExceptionally()) {
            final String errMsg = "SBK-GEM, command:  copy command failed!";
            Printer.log.error(errMsg);
            throw new InterruptedException(errMsg);
        }

        Printer.log.info("Copy command Success..");

        ramBenchmark.start();

        final SshResponse[] sbkResults = createMultiSshResponse(nodes.length, true);

        final String sbkDir = Paths.get(params.getSbkDir()).getFileName().toString();
        final String sbkCommand = sbkDir + "/" + GemConfig.BIN_EXT_PATH + "/" + params.getSbkCommand()+" "+sbkArgs;
        Printer.log.info("sbk command : " +sbkCommand);
        for (int i = 0; i < nodes.length; i++) {
            cfArray[i] = nodes[i].runCommandAsync(nodes[i].connection.getDir()+"/"+sbkCommand,
                    config.remoteTimeoutSeconds, sbkResults[i]);
        }
        final CompletableFuture<Void> sbkFuture = CompletableFuture.allOf(cfArray);
        sbkFuture.exceptionally(ex -> {
           shutdown(ex);
           return null;
        });

        sbkFuture.thenAccept(x -> {
            shutdown(null);
        });

        return retFuture;
    }


    private static int parseJavaVersion( String text) {
        if (StringUtils.isEmpty(text)) {
            return Integer.MAX_VALUE;
        }
        final String[] tmp = text.split("\"", 2);
        return  Integer.parseInt(tmp[1].split("\\.")[0]);
    }


    private static SshResponse[] createMultiSshResponse(int length, boolean stdout) {
        final SshResponse[] results = new SshResponse[length];
        for (int i = 0; i < results.length; i++) {
            results[i] = new SshResponse(stdout);
        }
        return results;
    }


    /**
     * Shutdown SBK Benchmark.
     *
     * closes all writers/readers.
     * closes the storage device/client.
     *
     */
    @Synchronized
    private void shutdown(Throwable ex) {
        if (state != State.END) {
            state = State.END;
            ramBenchmark.stop();
            if (ex != null) {
                Printer.log.warn("SBK GEM Benchmark Shutdown with Exception " + ex);
                retFuture.completeExceptionally(ex);
            } else {
                Printer.log.info("SBK GEM Benchmark Shutdown");
                retFuture.complete(null);
            }
        }
    }


    @Override
    public void stop() {
        for (SbkSsh node: nodes) {
            node.stop();
        }
        shutdown(null);
    }
}