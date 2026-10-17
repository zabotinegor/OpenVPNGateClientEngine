/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package de.blinkt.openvpn.core;

/**
 * Created by arne on 15.11.16.
 */

interface IOpenVPNServiceInternal {

    boolean protect(int fd);

    void userPause(boolean b);

    /**
     * @param replaceConnection True if the VPN is connected by a new connection.
     * @return true if there was a process that has been send a stop signal
     */
    boolean stopVPN(boolean replaceConnection);

    void addAllowedExternalApp(String packagename);

    boolean isAllowedExternalApp(String packagename);

    void challengeResponse(String response);

    /**
     * Terminates the native OpenVPN process without using the management channel. Intended as
     * the escalation when {@link #stopVPN} returned false although the tunnel is still up.
     * Non-blocking and idempotent; completion is reported as LEVEL_NOTCONNECTED.
     * Appended last so the existing transaction codes are unchanged.
     *
     * @return 0 no process was running, 1 termination requested, 2 not supported by this core
     */
    int forceStopVPN();
}
