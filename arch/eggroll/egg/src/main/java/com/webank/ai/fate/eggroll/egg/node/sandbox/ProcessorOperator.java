/*
 * Copyright 2019 The FATE Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.webank.ai.fate.eggroll.egg.node.sandbox;

import com.webank.ai.fate.core.server.ServerConf;
import com.webank.ai.fate.core.utils.RuntimeUtils;
import org.apache.commons.io.output.FileWriterWithEncoding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

@Component
/**
 *
 */
public class ProcessorOperator {
    @Autowired
    private ServerConf serverConf;
    @Autowired
    private RuntimeUtils runtimeUtils;

    private String startScriptPath;
    private String stopScriptPath;

    private String startCmdTemplate;
    private String stopCmdTemplate;

    private String processLogDir;
    private volatile boolean inited;

    private static final String startCmdScriptTemplate = "#!/bin/bash;source %s/bin/activate;export PYTHONPATH=$PYTHONPATH:%s;python %s -p $1 -d %s >> %s/processor-$1.log 2>&1 &";
    private static final String stopCmdScriptTemplate = "#!/bin/bash;kill -9 $(lsof -t -i:$1)";

    public void init() throws IOException {

        Properties properties = serverConf.getProperties();
        String venv = properties.getProperty("processor.venv");
        String dataDir = properties.getProperty("data.dir");
        String processorPath = properties.getProperty("processor.path");
        String pythonPath = properties.getProperty("python.path");

        processLogDir = properties.getProperty("processor.log.dir", pythonPath + "/logs");

        File tempStartScript = File.createTempFile("python-processor-starter-", ".sh");
        tempStartScript.deleteOnExit();
        String startScriptContent = String.format(startCmdScriptTemplate, venv, pythonPath, processorPath, dataDir, processLogDir).replace(";", "\n");

        try (BufferedWriter bw = new BufferedWriter(new FileWriterWithEncoding(tempStartScript, StandardCharsets.UTF_8))) {
            bw.write(startScriptContent);
            bw.flush();
        }

        File tempStopScript = File.createTempFile("python-processor-stopper-", ".sh");
        tempStopScript.deleteOnExit();
        try (BufferedWriter bw = new BufferedWriter(new FileWriterWithEncoding(tempStopScript, StandardCharsets.UTF_8))) {
            bw.write(stopCmdScriptTemplate.replace(";", "\n"));
            bw.flush();
        }

        startScriptPath = tempStartScript.getAbsolutePath();
        stopScriptPath = tempStopScript.getAbsolutePath();

        System.out.println(startScriptPath);
        System.out.println(stopScriptPath);
        this.startCmdTemplate = "sh " + startScriptPath + " %d";
        this.stopCmdTemplate = "sh " + stopScriptPath + " %d";

        inited = true;
    }

    public Process startProcessor(int port) throws IOException {
        if (!inited) {
            init();
        }

        Process processor = null;
        if (runtimeUtils.isPortAvailable(port)) {
            String cmd = String.format(startCmdTemplate, port, port);
            System.out.println(cmd);
            processor = Runtime.getRuntime().exec(cmd);
        } else {
            throw new SocketException("Address already in use: " + port);
        }

        return processor;
    }

    public boolean stopProcessor(int port) throws IOException, InterruptedException {
        if (!inited) {
            init();
        }
        boolean result = false;
        if (!runtimeUtils.isPortAvailable(port)) {
            String cmd = String.format(stopCmdTemplate, port);
            System.out.println(cmd);
            Process process = Runtime.getRuntime().exec(cmd);

            int returnCode = process.waitFor();

            if (runtimeUtils.isPortAvailable(port) && returnCode == 0) {
                result = true;
            }
        } else {
            result = true;
        }

        return result;
    }
}
