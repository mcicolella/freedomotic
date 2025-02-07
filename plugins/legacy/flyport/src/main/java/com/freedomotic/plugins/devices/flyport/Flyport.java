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
package com.freedomotic.plugins.devices.flyport;

import com.freedomotic.api.EventTemplate;
import com.freedomotic.api.Protocol;
import com.freedomotic.app.Freedomotic;
import com.freedomotic.events.ProtocolRead;
import com.freedomotic.exceptions.UnableToExecuteException;
import com.freedomotic.reactions.Command;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * A sensor for the board Flyport developed by openpicus.com
 *
 * @author Mauro Ccolella
 */
public class Flyport extends Protocol {

    private static final Logger LOG = LoggerFactory.getLogger(Flyport.class.getName());
    private static ArrayList<Board> boards = null;
    private static int BOARD_NUMBER = 1;
    private static int POLLING_TIME = 1000;
    private Socket socket = null;
    private DataOutputStream outputStream = null;
    private BufferedReader inputStream = null;
    private String[] address = null;
    private int SOCKET_TIMEOUT = configuration.getIntProperty("socket-timeout", 1000);

    /**
     * Initializations
     */
    public Flyport() {
        super("Flyport", "/flyport/flyport-manifest.xml");
        setPollingWait(POLLING_TIME);
    }

    private void loadBoards() {
        if (boards == null) {
            boards = new ArrayList<Board>();
        }
        setDescription("Reading status changes from"); //empty description
        for (int i = 0; i < BOARD_NUMBER; i++) {
            String ipToQuery;
            String lineToMonitorize;
            int portToQuery;
            int ledNumber;
            int btnNumber;
            int potNumber;
            int startingValue;
            ipToQuery = configuration.getTuples().getStringProperty(i, "ip-to-query", "192.168.0.115");
            portToQuery = configuration.getTuples().getIntProperty(i, "port-to-query", 80);
            lineToMonitorize = configuration.getTuples().getStringProperty(i, "line-to-monitorize", "led");
            potNumber = configuration.getTuples().getIntProperty(i, "pot-number", 2);
            ledNumber = configuration.getTuples().getIntProperty(i, "led-number", 5);
            btnNumber = configuration.getTuples().getIntProperty(i, "btn-number", 5);
            startingValue = configuration.getTuples().getIntProperty(i, "starting-value", 0);
            Board board = new Board(ipToQuery, portToQuery, potNumber, ledNumber,
                    btnNumber, lineToMonitorize, startingValue);
            boards.add(board);
            setDescription(getDescription() + " " + ipToQuery + ":" + portToQuery + ":" + lineToMonitorize + ";");
        }
    }

    /**
     * Connection to boards
     */
    private boolean connect(String address, int port) {

        LOG.info("Trying to connect to flyport board on address {}:{}", address, port);
        try {
            //TimedSocket is a non-blocking socket with timeout on exception
            socket = TimedSocket.getSocket(address, port, SOCKET_TIMEOUT);
            socket.setSoTimeout(SOCKET_TIMEOUT); //SOCKET_TIMEOUT ms of waiting on socket read/write
            BufferedOutputStream buffOut = new BufferedOutputStream(socket.getOutputStream());
            outputStream = new DataOutputStream(buffOut);
            return true;
        } catch (IOException e) {
            LOG.error("Unable to connect to host {} on port {}", address, port);
            return false;
        }
    }

    private void disconnect() {
        // close streams and socket
        try {
            inputStream.close();
            outputStream.close();
            socket.close();
        } catch (Exception ex) {
            //do nothing. Best effort
        }
    }

    /**
     * Sensor side
     */
    @Override
    public void onStart() {
        POLLING_TIME = configuration.getIntProperty("polling-time", 1000);
        BOARD_NUMBER = configuration.getTuples().size();
        setPollingWait(POLLING_TIME);
        loadBoards();
    }

    @Override
    public void onStop() {
        //release resources
        boards.clear();
        boards = null;
        setPollingWait(-1); //disable polling
        //display the default description
        setDescription(configuration.getStringProperty("description", "Flyport"));
    }

    @Override
    protected void onRun() {
        for (Board board : boards) {
            evaluateDiffs(getXMLStatusFile(board), board); //parses the xml and crosscheck the data with the previous read
            try {
                Thread.sleep(POLLING_TIME);
            } catch (InterruptedException ex) {
                LOG.error(ex.getLocalizedMessage(), ex);
                Thread.currentThread().interrupt();
            }
        }
    }

    private Document getXMLStatusFile(Board board) {
        //get the xml file from the socket connection
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = null;
        try {
            dBuilder = dbFactory.newDocumentBuilder();
        } catch (ParserConfigurationException ex) {
            LOG.error(ex.getLocalizedMessage());
        }
        Document doc = null;
        String statusFileURL = null;
        try {
            statusFileURL = "http://" + board.getIpAddress() + ":"
                    + Integer.toString(board.getPort()) + "/status.xml";
            LOG.info("Flyport gets relay status from file {}", statusFileURL);
            doc = dBuilder.parse(new URL(statusFileURL).openStream());
            doc.getDocumentElement().normalize();
        } catch (ConnectException connEx) {
            disconnect();
            this.stop();
            this.setDescription("Connection timed out, no reply from the board at " + statusFileURL);
        } catch (SAXException ex) {
            disconnect();
            this.stop();
            LOG.error(Freedomotic.getStackTraceInfo(ex));
        } catch (Exception ex) {
            disconnect();
            this.stop();
            setDescription("Unable to connect to " + statusFileURL);
            LOG.error(Freedomotic.getStackTraceInfo(ex));
        }
        return doc;
    }

    private void evaluateDiffs(Document doc, Board board) {
        //parses xml
        if (doc != null && board != null) {
            Node n = doc.getFirstChild();
            NodeList nl = n.getChildNodes();
            int startingValue = board.getStartingValue();
            String lineToMonitorize = board.getLineToMonitorize();
            int linesNumber = 0;
            if (lineToMonitorize.equalsIgnoreCase("led")) {
                linesNumber = board.getLedNumber();
            }
            for (int i = startingValue; i <= linesNumber; i++) {
                try {
                    // converts i into hexadecimal value (string) and sends the parameters
                    String tagName = board.getLineToMonitorize() + HexIntConverter.convert(i);
                    LOG.debug("Flyport monitorizes tags {}", tagName);
                    sendChanges(i, board, doc.getElementsByTagName(tagName).item(0).getTextContent());
                } catch (DOMException dOMException) {
                    //do nothing
                } catch (NumberFormatException numberFormatException) {
                    //do nothing
                } catch (NullPointerException ex) {
                    //do nothing
                }
            }
        }
    }

    private void sendChanges(int relayLine, Board board, String status) {
        //reconstruct freedomotic object address
        String address = board.getIpAddress() + ":" + board.getPort() + ":" + relayLine;
        LOG.info("Sending Flyport protocol read event for object address '{}'. It's readed status is {}", address, status);
        //building the event
        ProtocolRead event = new ProtocolRead(this, "flyport", address); //IP:PORT:RELAYLINE
        // relay lines - status=0 -> off; status=1 -> on
        if (board.getLineToMonitorize().equalsIgnoreCase("led")) {
            if (status.equals("0")) {
                event.addProperty("isOn", "false");
            } else {
                event.addProperty("isOn", "true");
            }
        } else if (board.getLineToMonitorize().equalsIgnoreCase("btn")) {
            if (status.equalsIgnoreCase("0")) {
                event.addProperty("isOn", "false");
            } else {
                event.addProperty("isOn", "true");
            }
        } else {
            if (board.getLineToMonitorize().equalsIgnoreCase("pot")) {
                if (status.equalsIgnoreCase("0")) {
                    event.addProperty("isOn", "false");
                } else {
                    event.addProperty("isOn", "true");
                }
            }
        }
        //adding some optional information to the event
        event.addProperty("boardIP", board.getIpAddress());
        event.addProperty("boardPort", new Integer(board.getPort()).toString());
        event.addProperty("relayLine", new Integer(relayLine).toString());
        //publish the event on the messaging bus
        this.notifyEvent(event);
    }

    /**
     * Actuator side
     *
     * @throws com.freedomotic.exceptions.UnableToExecuteException
     */
    @Override
    public void onCommand(Command c) throws UnableToExecuteException {
        //get connection paramentes address:port from received freedom command
        String delimiter = configuration.getProperty("address-delimiter");
        address = c.getProperty("address").split(delimiter);
        //connect to the ethernet board
        boolean connected = false;
        try {
            connected = connect(address[0], Integer.parseInt(address[1]));
        } catch (ArrayIndexOutOfBoundsException outEx) {
            LOG.error("The object address '" + c.getProperty("address") + "' is not properly formatted. Check it!");
            throw new UnableToExecuteException();
        } catch (NumberFormatException numberFormatException) {
            LOG.error(address[1] + " is not a valid ethernet port to connect to");
            throw new UnableToExecuteException();
        }

        if (connected) {
            String message = createMessage(c);
            String expectedReply = c.getProperty("expected-reply");
            try {
                String reply = sendToBoard(message);
                if ((reply != null) && (!reply.equals(expectedReply))) {
                    //TODO: implement reply check
                }
            } catch (IOException iOException) {
                setDescription("Unable to send the message to host " + address[0] + " on port " + address[1]);
                LOG.error("Unable to send the message to host " + address[0] + " on port " + address[1]);
                throw new UnableToExecuteException();
            } finally {
                disconnect();
            }
        } else {
            throw new UnableToExecuteException();
        }
    }

    private String sendToBoard(String message) throws IOException {
        String receivedReply = null;
        if (outputStream != null) {
            outputStream.writeBytes(message);
            outputStream.flush();
            inputStream = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            try {
                receivedReply = inputStream.readLine(); // read device reply
            } catch (IOException iOException) {
                throw new IOException();
            }
        }
        return receivedReply;
    }

    // create message to send to the board
    // this part must be changed to relect board protocol
    /**
     *
     * @param c
     * @return
     */
    public String createMessage(Command c) {
        String message = null;
        String page = null;
        String behavior = null;
        String relay = null;

        if (c.getProperty("command").equals("RELAY")) {
            //relay = HexIntConverter.convert(Integer.parseInt(address[2]) - 1);
            relay = HexIntConverter.convert(Integer.parseInt(address[2]));
            page = "leds.cgi?led=" + relay;
        }
        // http request sending to the board
        message = "GET /" + page + " HTTP 1.1\r\n\r\n";
        LOG.info("Sending 'GET /" + page + " HTTP 1.1' to Flyport board");
        return (message);
    }

    @Override
    protected boolean canExecute(Command c) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    protected void onEvent(EventTemplate event) {
        throw new UnsupportedOperationException("Not supported yet.");
    }
}
