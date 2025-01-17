/**
 * Copyright (c) KMG. All Rights Reserved..
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.sbk.yal.impl;

import io.sbk.config.YalConfig;
import io.sbk.exception.HelpException;
import io.sbk.options.impl.SbkInputOptions;
import io.sbk.yal.YalParameters;
import org.apache.commons.cli.ParseException;

public class SbkYalParameters extends SbkInputOptions implements YalParameters {
    protected YalConfig config;

    public SbkYalParameters(String name, String desc, YalConfig config) {
        super(name, desc);
        this.config = config;
        addOption("f", true, getFileOptionDescription());
    }

    public String getFileOptionDescription() {
        return "SBK YAML file, default: " + config.yamlFileName;
    }

    @Override
    public String getFileName() {
        return this.config.yamlFileName;
    }

    @Override
    public void parseArgs(String[] args) throws ParseException, IllegalArgumentException, HelpException {
        super.parseArgs(args);
        config.yamlFileName = getOptionValue("f", config.yamlFileName);
    }
}
