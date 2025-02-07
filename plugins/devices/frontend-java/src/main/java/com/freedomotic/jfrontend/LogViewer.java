/**
 *
 * Copyright (c) 2009-2022 Freedomotic Team http://www.freedomotic-platform.com
 *
 * This file is part of Freedomotic
 *
 * This Program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2, or (at your option) any later version.
 *
 * This Program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * Freedomotic; see the file COPYING. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.freedomotic.jfrontend;

import java.io.IOException;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import com.freedomotic.api.EventTemplate;
import com.freedomotic.api.Protocol;
import com.freedomotic.exceptions.UnableToExecuteException;
import com.freedomotic.reactions.Command;


/**
 *
 * @author Enrico Nicoletti
 */
public class LogViewer extends Protocol {

    //get root logger
    private static final Logger logger = Logger.getLogger("com.freedomotic");

    private Log4jAppender handler = null;

    /**
     *
     */
    public LogViewer() {
        super("Log Viewer", "/frontend-java/logviewer-manifest.xml");
        setPollingWait(-1); //disable threaded onRun()
    }

    @Override
    protected void onShowGui() {
        //nothing special to do here
    }

    @Override
    protected void onStart() {
    	logger.setLevel(Level.ALL);
        handler = Log4jAppender.getInstance(getApi().getI18n(), logger);
        logger.addAppender(handler);
        bindGuiToPlugin(handler.window);
    }

    @Override
    protected void onStop() {
        //free memory
        hideGui();
        gui = null;
        logger.removeAppender(handler);
        handler = null;
    }

    @Override
    protected void onCommand(Command c)
            throws IOException, UnableToExecuteException {
        //do nothing
    }

    @Override
    protected boolean canExecute(Command c) {
        //do nothing
        return true;
    }

    @Override
    protected void onRun() {
        //do nothing
    }

    @Override
    protected void onEvent(EventTemplate event) {
        //do nothing
    }
}
