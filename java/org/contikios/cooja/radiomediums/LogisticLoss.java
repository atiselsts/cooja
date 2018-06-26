/*
 * Copyright (c) 2018, University of Bristol
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the Institute nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE INSTITUTE AND CONTRIBUTORS ``AS IS'' AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE INSTITUTE OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 *
 */

package org.contikios.cooja.radiomediums;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Observable;
import java.util.Observer;
import java.util.Random;

import org.apache.log4j.Logger;
import org.jdom.Element;

import org.contikios.cooja.ClassDescription;
import org.contikios.cooja.Mote;
import org.contikios.cooja.RadioConnection;
import org.contikios.cooja.SimEventCentral.MoteCountListener;
import org.contikios.cooja.Simulation;
import org.contikios.cooja.interfaces.Position;
import org.contikios.cooja.interfaces.Radio;
import org.contikios.cooja.plugins.Visualizer;
import org.contikios.cooja.plugins.skins.LogisticLossVisualizerSkin;

/**
 * The received radio packet signal strength grows inversely with the distance to the
 * transmitter: `rssi_dBm = a + (1 - d) * b`, where `d` is the normalized distance.
 *
 * The packet reception rate is modeled following the function `k_1 / (1 + exp(-rssi * k_2))`.
 *
 * @see UDGM
 * @author Atis Elsts
 * @author Fredrik Osterlind
 */
@ClassDescription("Logistic Loss Medium")
public class LogisticLoss extends AbstractRadioMedium {
    private static Logger logger = Logger.getLogger(LogisticLoss.class);

    /* At this distance, the RSSI is minimal */
    public final double TRANSMITTING_RANGE = 10.0;
    public final double INTERFERENCE_RANGE = TRANSMITTING_RANGE;

    /* At this RSSI, 50% of packets are received */
    private final double PRR_FIFTY_PERCENT_OFFSET = -75.0;
    /* Scaling of the slope of the packet loss curve. */
    /* Must be in range from 1.0 to 0.0. The smaller this number, the less steep is the curve. */
    private final double PRR_SCALING_FACTOR = 0.2;

    /* The range from ~0% PRR to ~100% PRR */
    private final double RSSI_RANGE = 50.0;
    /* close to 0% PRR */
    private final double MIN_RSSI = -97.0;
    /* close to 100% PRR */
    private final double MAX_RSSI = MIN_RSSI + RSSI_RANGE;

    /* The co-channel rejection threshold of 802.15.4 radios typically is -3 dB */
    private final double CO_CHANNEL_REJECTION = -3.0;

    private final int WEARABLE_OFFSET = 192;


    private DirectedGraphMedium dgrm; /* Used only for efficient destination lookup */

    private Random random = null;

    /*
     * This is a hack to make sure that GW<->GW communication always works.
     * Since the goal of these simulations is to evaluate the mobile part of the network,
     * we assume that the infrastructure network is always able to receive packets
     * (this is not unrealistic as in the real work the devices usually have better antennas etc.
     * and the infrastructure network has plenty of time to settle down and establish routes etc.)
     */
    private boolean bothAreGateways(Radio source, Radio dest) {
        if(source.getMote().getID() < WEARABLE_OFFSET
                && dest.getMote().getID() < WEARABLE_OFFSET) {
            return true;
        }
        return false;
    }

    public LogisticLoss(Simulation simulation) {
        super(simulation);
        random = simulation.getRandomGenerator();
        dgrm = new DirectedGraphMedium() {
                protected void analyzeEdges() {
                    /* Create edges according to distances.
                     * XXX May be slow for mobile networks */
                    clearEdges();
                    for (Radio source: LogisticLoss.this.getRegisteredRadios()) {
                        Position sourcePos = source.getPosition();
                        for (Radio dest: LogisticLoss.this.getRegisteredRadios()) {
                            Position destPos = dest.getPosition();
                            /* Ignore ourselves */
                            if (source == dest) {
                                continue;
                            }
                            double distance = sourcePos.getDistanceTo(destPos);
                            if (distance < TRANSMITTING_RANGE || bothAreGateways(source, dest)) {
                                /* Add potential destination */
                                addEdge(
                                        new DirectedGraphMedium.Edge(source, 
                                                new DGRMDestinationRadio(dest)));
                            }
                        }
                    }
                    super.analyzeEdges();
                }
            };

        /* Register as position observer.
         * If any positions change, re-analyze potential receivers. */
        final Observer positionObserver = new Observer() {
                public void update(Observable o, Object arg) {
                    dgrm.requestEdgeAnalysis();
                }
            };
        /* Re-analyze potential receivers if radios are added/removed. */
        simulation.getEventCentral().addMoteCountListener(new MoteCountListener() {
                public void moteWasAdded(Mote mote) {
                    mote.getInterfaces().getPosition().addObserver(positionObserver);
                    dgrm.requestEdgeAnalysis();
                }
                public void moteWasRemoved(Mote mote) {
                    mote.getInterfaces().getPosition().deleteObserver(positionObserver);
                    dgrm.requestEdgeAnalysis();
                }
            });
        for (Mote mote: simulation.getMotes()) {
            mote.getInterfaces().getPosition().addObserver(positionObserver);
        }
        dgrm.requestEdgeAnalysis();

        /* Register visualizer skin */
        Visualizer.registerVisualizerSkin(LogisticLossVisualizerSkin.class);
    }

    public void removed() {
        super.removed();

        Visualizer.unregisterVisualizerSkin(LogisticLossVisualizerSkin.class);
    }
  
    public RadioConnection createConnections(Radio sender) {
        RadioConnection newConnection = new RadioConnection(sender);

        /* Get all potential destination radios */
        DestinationRadio[] potentialDestinations = dgrm.getPotentialDestinations(sender);
        if (potentialDestinations == null) {
            return newConnection;
        }

        /* Loop through all potential destinations */
        Position senderPos = sender.getPosition();
        for (DestinationRadio dest: potentialDestinations) {
            Radio recv = dest.radio;

            /* If both gateways, ignore things like channel, distance etc. */
            if(bothAreGateways(sender, recv)) {
                /* Success: radio starts receiving */
                newConnection.addDestination(recv);
                continue;
            }

            /* Fail if radios are on different (but configured) channels */ 
            if (sender.getChannel() >= 0 &&
                    recv.getChannel() >= 0 &&
                    sender.getChannel() != recv.getChannel()) {

                /* Add the connection in a dormant state;
                   it will be activated later when the radio will be
                   turned on and switched to the right channel. This behavior
                   is consistent with the case when receiver is turned off. */
                newConnection.addInterfered(recv);

                continue;
            }
            Position recvPos = recv.getPosition();

            double distance = senderPos.getDistanceTo(recvPos);
            if (distance <= TRANSMITTING_RANGE) {
                /* Within transmission range */

                if (!recv.isRadioOn()) {
                    newConnection.addInterfered(recv);
                    recv.interfereAnyReception();
                } else if (recv.isInterfered()) {
                    /* Was interfered: keep interfering */
                    newConnection.addInterfered(recv);
                } else if (recv.isTransmitting()) {
                    newConnection.addInterfered(recv);
                } else {
                    boolean receiveNewOk = random.nextDouble() < getRxSuccessProbability(sender, recv);

                    if (recv.isReceiving()) {
                        /*
                         * Compare new and old and decide whether to interfere.
                         * XXX: this is a simplified check. Rather than looking at all N potential senders,
                         * it looks at just this and the strongest one of the previous transmissions
                         * (since updateSignalStrengths() updates the signal strength iff the previous one is weaker)
                        */

                        double oldSignal = recv.getCurrentSignalStrength();
                        double newSignal = getRSSI(sender, recv);

                        boolean doInterfereOld;

                        if(oldSignal + CO_CHANNEL_REJECTION > newSignal) {
                            /* keep the old transmission */
                            doInterfereOld = false;
                            receiveNewOk = false;
                            /* logger.info(sender + ": keep old " + recv); */
                        } else if (newSignal + CO_CHANNEL_REJECTION > oldSignal) {
                            /* keep the new transmission */
                            doInterfereOld = true;
                            /* logger.info(sender + ": keep new " + recv); */
                        } else {
                            /* too equal strengths; none gets through */
                            doInterfereOld = true;
                            receiveNewOk = false;

                            /* logger.info(sender + ": interfere both " + recv); */

                            /* XXX: this will interfere even if later a stronger connections
                             * comes ahead that could override all existing weaker connections! */
                            recv.interfereAnyReception();
                        }

                        if(doInterfereOld) {
                            /* Find all existing connections and interfere them */
                            for (RadioConnection conn : getActiveConnections()) {
                                if (conn.isDestination(recv)) {
                                    conn.addInterfered(recv);
                                }
                            }

                            recv.interfereAnyReception();
                        }
                    }

                    if(receiveNewOk) {
                        /* Success: radio starts receiving */
                        newConnection.addDestination(recv);
                        /* logger.info(sender + ": tx to " + recv); */
                    } else {
                        newConnection.addInterfered(recv);
                        /* logger.info(sender + ": interfere to " + recv); */
                    }
                }
            }
        }

        return newConnection;
    }
  
    public double getSuccessProbability(Radio source, Radio dest) {
        return getRxSuccessProbability(source, dest);
    }

    public double getRxSuccessProbability(Radio source, Radio dest) {
        double distance = source.getPosition().getDistanceTo(dest.getPosition());
        double normalizedDistance = Math.min(1.0, distance / TRANSMITTING_RANGE);

        double rssi = MIN_RSSI + (1 - normalizedDistance) * RSSI_RANGE;

        return modelLinkPRR(rssi);
    }

    /* This returns the PRR using logistic function on the RSSI */
    public double modelLinkPRR(double rssi) {
        if (rssi <= MIN_RSSI) {
            return 0.0;
        }
        if (rssi <= MIN_RSSI + 1.0) {
            /* as an approximation: very weak anyway */
            return modelGoodLinkPRR(rssi);
        }
        if (rssi >= MAX_RSSI + 5.0) {
            /* always receive */
            return 1.0;
        }
        /* center to zero, but with the given offset */
        double x = rssi - PRR_FIFTY_PERCENT_OFFSET;
        /* divide x by `scaling_factor` to get more flat curve (some errors up to MAX_RSSI) */
        return 1.0 / (1.0 + Math.exp(-x * PRR_SCALING_FACTOR));
    }

    public double modelGoodLinkPRR(double rssi) {
        /* center to zero (assuming 50% offset at 5dB above MIN_RSSI) */
        double x = rssi - (MIN_RSSI + 5.0);
        return 1.0 / (1.0 + Math.exp(-x));
    }

    /* Additive White Gaussian Noise, sampled from the distribution N(0.0, 1.0) */
    private double getAWGN() {
        return random.nextGaussian();
    }

    private double getRSSI(Radio source, Radio dst) {
        double distance = source.getPosition().getDistanceTo(dst.getPosition());
        double normalizedDistance = Math.min(1.0, distance / TRANSMITTING_RANGE);

        double rssi = MIN_RSSI + (1 - normalizedDistance) * RSSI_RANGE;

        return rssi + getAWGN();
    }

    public void updateSignalStrengths() {
        /* Override: uses distance as signal strength factor */
    
        /* Reset signal strengths */
        for (Radio radio : getRegisteredRadios()) {
            radio.setCurrentSignalStrength(getBaseRssi(radio));
        }

        /* Set signal strength to below strong on destinations */
        RadioConnection[] conns = getActiveConnections();
        for (RadioConnection conn : conns) {
            if (conn.getSource().getCurrentSignalStrength() < SS_STRONG) {
                conn.getSource().setCurrentSignalStrength(SS_STRONG);
            }
            for (Radio dstRadio : conn.getDestinations()) {

                /* Specialcase for the gateways. */
                if(bothAreGateways(conn.getSource(), dstRadio)) {
                    if (dstRadio.getCurrentSignalStrength() < SS_STRONG) {
                        dstRadio.setCurrentSignalStrength(SS_STRONG);
                    }
                    continue;
                }

                if (conn.getSource().getChannel() >= 0 &&
                        dstRadio.getChannel() >= 0 &&
                        conn.getSource().getChannel() != dstRadio.getChannel()) {
                    continue;
                }

                double rssi = getRSSI(conn.getSource(), dstRadio);
                if (dstRadio.getCurrentSignalStrength() < rssi) {
                    dstRadio.setCurrentSignalStrength(rssi);
                }
            }
        }

        /* Set signal strength to below weak on interfered */
        for (RadioConnection conn : conns) {
            for (Radio intfRadio : conn.getInterfered()) {
                if (conn.getSource().getChannel() >= 0 &&
                        intfRadio.getChannel() >= 0 &&
                        conn.getSource().getChannel() != intfRadio.getChannel()) {
                    continue;
                }

                double rssi = getRSSI(conn.getSource(), intfRadio);
                if (intfRadio.getCurrentSignalStrength() < rssi) {
                    intfRadio.setCurrentSignalStrength(rssi);
                }

                /* XXX: this should be uncommented if there is a desire to see broken packets / false wakeups in all cases, not just in the case of collision, as happes at the moment */

                /* 
                if (!intfRadio.isInterfered()) {
                    logger.warn("Radio was not interfered: " + intfRadio);
                    intfRadio.interfereAnyReception();
                }
                */
            }
        }
    }

    public Collection<Element> getConfigXML() {
        Collection<Element> config = super.getConfigXML();
        Element element;

        /* For future extensions: if any config, get it here */

        return config;
    }

    public boolean setConfigXML(Collection<Element> configXML, boolean visAvailable) {
        super.setConfigXML(configXML, visAvailable);
        for (Element element : configXML) {
            /* For future extensions: if any config, set it here */
        }
        return true;
    }

}
