/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

 package com.android.server;

 import java.io.File;
 import java.net.Inet6Address;
 import java.io.IOException;
 import java.io.InterruptedIOException;
 import java.net.UnknownHostException;
 import android.system.ErrnoException;
 import android.util.Log;
 import java.util.*;
 import java.nio.ByteBuffer;
 import java.io.FileDescriptor;

 import android.net.IpPrefix;

 import android.os.ParcelFileDescriptor;

 import android.system.Os;
 import android.system.OsConstants;
 import android.system.StructPollfd;

 import com.android.net.module.util.netlink.NetlinkUtils;

 import android.os.Handler;
 import android.os.HandlerThread;
 import android.os.Looper;

 import java.time.Instant;

 import com.android.net.module.util.PacketReader;
/* import com.android.internal.util.StateMachine;
 import com.android.net.module.util.InterfaceParams;
 import android.os.Handler;
 import static android.system.OsConstants.AF_PACKET;
 import static android.system.OsConstants.SOCK_NONBLOCK;
 import static android.system.OsConstants.SOCK_RAW;*/

 /**
  *
  */
 public class MulticastRoutingCoordinator {
    static {
        System.loadLibrary("service-connectivity");
    }

    private static final String TAG =  "MulticastRoutingCoordinator";

    private static final int MRT_OFFSET = 1000;
    private static boolean isEnabled = false;

    private static int numOfTables = 0;
    //private ArrayList<ParcelFileDescriptor> mFileDescriptors = new ArrayList<ParcelFileDescriptor>();

    private ParcelFileDescriptor mFD;
    private CoordinatorWorker mThread;
    private boolean threadRunning = false;

    private final HandlerThread mHandlerThread;
    private final HandlerThread mListenerThread;
    private final Handler mTimeoutHandler;
    private final Handler mPacketListenerHandler;

    private ParcelFileDescriptor adoptFd(int fd) {
        return ParcelFileDescriptor.adoptFd(fd);
    }

    private final PacketListener mPacketListener;
    private Map<String, Byte> vifIdx = new HashMap<String, Byte>();
    private Map<Byte, String> vifIdxToIfName = new HashMap<>();
    private static byte nextVifIdx = 1;

    private byte getVifIdx(String ifName) {
        if (vifIdx.get(ifName) == null) {
            vifIdx.put(ifName, nextVifIdx);
            vifIdxToIfName.put(nextVifIdx, ifName);
            nativeAddMif(mFD.getFileDescriptor(), nextVifIdx, ifName);
            nextVifIdx++;
        }
        return vifIdx.get(ifName);
    }

    private String getIfName(byte vifIdx) {
        if (vifIdxToIfName.get(vifIdx) != null) {
            return vifIdxToIfName.get(vifIdx);
        }
        return "";
    }

    Runnable startListener = new Runnable() {
        public void run() {
            mPacketListener.start();
        }
    };

     public MulticastRoutingCoordinator() {
         mHandlerThread = new HandlerThread("MulticastRoutingCoordinatorThread");
         mListenerThread = new HandlerThread("MulticastRoutingCoordinatorListener");
         mHandlerThread.start();
         mListenerThread.start();
         mTimeoutHandler = new Handler(mHandlerThread.getLooper());
         mTimeoutHandler.postDelayed(timeoutChecker, 1000);
         mFD = adoptFd(initSocket());
         mPacketListenerHandler = new Handler(mListenerThread.getLooper());
         mPacketListener = new PacketListener(mPacketListenerHandler, mFD.getFileDescriptor());
         mPacketListenerHandler.post(startListener);
     }
    private class MulticatRoutingConfig {
        // new oif's mifi is existing number of oifs + 1
        // when removing a oif, change the last oif's mifi to the removed one
        byte mifi;
        boolean mAllowAllAddresses = false;
        int mMinScope = 4;
        ArrayList<Inet6Address> mAllowedAddresses;
        ArrayList<IpPrefix> mBlockedSrcPrefixes;
    };

    private class MulticastForwardingCache {
        byte[] mSrcAddr;
        byte[] mDstAddr;
        int mIifVirtualIdx;
        byte[] mOifVirtualIdxes;
        Instant mExpiresAt;

        MulticastForwardingCache(int iif, byte[] oifs, byte[] src, byte[] dst, Instant expiresAt) {
            mIifVirtualIdx = iif;
            mOifVirtualIdxes = oifs;
            mSrcAddr = src;
            mDstAddr = dst;
            mExpiresAt = expiresAt;
        }
    }

    private class MulticastRoutingTableInfo {
        ParcelFileDescriptor fd;
        int table;
        byte mifi;
        //boolean ruleCreated = false;
        // mapping of oif name to MulticatRoutingConfig
        Map<String, MulticatRoutingConfig> oifInfo = new HashMap<String, MulticatRoutingConfig>();

        private MulticatRoutingConfig getOifInfo(String oif) {
            return oifInfo.get(oif);
        }
    };

    private ArrayList<MulticastForwardingCache> mMFCs = new ArrayList<>();
    private Map<String, MulticastRoutingTableInfo> mroutingTableInfo = new HashMap<String, MulticastRoutingTableInfo>();

    private MulticastRoutingTableInfo getIifInfo(String iif) {
        return mroutingTableInfo.get(iif);
    }

    private class Mrt6Msg {
        public byte mMbz;
        public byte mMsgtype;
        public byte mMif;
        public Inet6Address mSrc;
        public Inet6Address mDst;

        public Mrt6Msg(byte[] bytes) {
            mMbz = bytes[0];
            mMsgtype = bytes[1];
            mMif = (byte)((((byte) 0xFF & bytes[3]) << 8) | ((byte) 0xFF & bytes[2]));
            try {
                byte[] srcBytes = new byte[16];
                System.arraycopy(bytes, 8, srcBytes, 0, 16);
                mSrc = Inet6Address.getByAddress(null, srcBytes, 0);
                byte[] dstBytes = new byte[16];
                System.arraycopy(bytes, 24, dstBytes, 0, 16);
                mDst = Inet6Address.getByAddress(null, dstBytes, 0);
            }  catch (UnknownHostException e) {
                Log.e(TAG, "unknown host");
            }
        }
    }

     private final class PacketListener extends PacketReader {
        private final FileDescriptor mFd;

        public PacketListener(Handler h, FileDescriptor fd) {
            super(h);
            mFd = fd;
        }

         @Override
         protected FileDescriptor createFd() {
            Log.i(TAG, "FD created.");
             return mFd;
         }

         @Override
         protected void handlePacket(byte[] recvbuf, int length) {
            Log.i(TAG, "handle packet with length = " + length);
             Mrt6Msg mrt6Msg = new Mrt6Msg(recvbuf);
             Log.i(TAG, "mif = " + mrt6Msg.mMif);
             Log.i(TAG, "Got nocache upcall in packetreader type = " + mrt6Msg.mMsgtype + " with src = "
                     + mrt6Msg.mSrc.getHostAddress() + ", dst = " + mrt6Msg.mDst.getHostAddress());
             int dstScope = mrt6Msg.mDst.getAddress()[1] & 0xf;
             Log.i(TAG, "Dst scope = " + dstScope + " dstAddr scopeid + " + mrt6Msg.mDst.getScopeId());
             MulticastRoutingTableInfo iinfo = mroutingTableInfo.get(getIfName(mrt6Msg.mMif));

             int iifVid = iinfo.mifi;
             Log.i(TAG, "iifid from iinfo = " + iifVid);

             if (iifVid == mrt6Msg.mMif) {

                 ArrayList<Byte> forwardingOifs = new ArrayList<Byte>();
                 for (Map.Entry<String, MulticatRoutingConfig> config : iinfo.oifInfo.entrySet()) {
                     if (config.getValue().mAllowAllAddresses && config.getValue().mMinScope <= dstScope) {
                         forwardingOifs.add(config.getValue().mifi);
                     }
                 }
                 if (forwardingOifs.size() > 0) {
                     byte[] oifs = new byte[forwardingOifs.size()];
                     for (int j = 0; j < forwardingOifs.size(); j++) {
                         oifs[j] = (byte) forwardingOifs.get(j);
                         Log.i(TAG, "added oif " + oifs[j]);
                     }
                     //oifs[forwardingOifs.size()] = iinfo.mifi;
                     byte[] anyAddr = new byte[16];
                     //nativeAddMfc(fds[i].fd, iifVid, oifs, mrt6Msg.mSrc.getAddress(), mrt6Msg.mDst.getAddress());
                     addMfc(iifVid, oifs, mrt6Msg.mSrc.getAddress(), mrt6Msg.mDst.getAddress(), Instant.now().plusSeconds(1));
                     addMfc(iifVid, oifs, anyAddr, mrt6Msg.mDst.getAddress(), Instant.now().plusSeconds(300));
                 }
             }
         }

         @Override
         protected void onStart() {
             Log.i(TAG, "Packet listener started");
         }

         @Override
         protected void onStop() {
             Log.i(TAG, "Packet listener stopped");
         }

     }
    private class CoordinatorWorker extends Thread {
        private String tag = "MulticastRoutingCoordinatorWorker";
        public void run() {
            Log.i(tag, "isEnabled = " + isEnabled);
            Log.i(tag, "numOfTables = " + numOfTables);
            

            while(true) {
                int i = 0;
                //Log.i(tag, "mroutingTableInfo size = " + mroutingTableInfo.size());
                //StructPollfd[] fds = new StructPollfd[mroutingTableInfo.size()];
                StructPollfd[] fds = new StructPollfd[1];
                //for (Map.Entry<String, MulticastRoutingTableInfo> info : mroutingTableInfo.entrySet()) {
                    fds[i] = new StructPollfd();
                    //fds[i].fd = info.getValue().fd.getFileDescriptor();
                fds[i].fd = mFD.getFileDescriptor();
                    fds[i].events = (short) OsConstants.POLLIN;
                    fds[i].revents = 0;
                    //fds[i].userData = info.getValue();
                    //Log.i(tag, "i = " + i + " key = " + info.getKey());
                //    i++;
                //}
                int res;
                try {
                    res = Os.poll(fds, 2000);
                } catch (ErrnoException e) {
                    Log.e(TAG, "Os.poll failed");
                    break;
                }
                if (res == 0) {
                    continue;
                }
                else if (res == -1) {
                    Log.e(TAG, "Os.poll() failed");
                    break;
                }
                for (i = 0; i < fds.length; i++) {
                    if ((fds[i].revents & OsConstants.POLLIN) != 0) {
                        Log.i(TAG, "Read event ready on socket " + fds[i].fd);
                        byte[] buf = new byte[40];
                        try{
                            int length =
                                Os.read(
                                        fds[i].fd,
                                        buf,
                                        0,
                                        buf.length);
                            Log.i(TAG, "Read " + length + " bytes from the socket");

                            if (length == 40 && buf[1] == 1) {
                                Mrt6Msg mrt6Msg = new Mrt6Msg(buf);
                                Log.i(TAG, "mif = " + mrt6Msg.mMif);
                                Log.i(TAG, "Got nocache upcall type = " + mrt6Msg.mMsgtype + " with src = "
                                            + mrt6Msg.mSrc.getHostAddress() + ", dst = " + mrt6Msg.mDst.getHostAddress());
                                int dstScope = mrt6Msg.mDst.getAddress()[1] & 0xf;
                                Log.i(TAG, "Dst scope = " + dstScope + " dstAddr scopeid + " + mrt6Msg.mDst.getScopeId());

                                //MulticastRoutingTableInfo iifInfo = mroutingTableInfo.get(fds[i].userData);
                                MulticastRoutingTableInfo iinfo = mroutingTableInfo.get(getIfName(mrt6Msg.mMif));

                                //byte[] mifBytes = Arrays.copyOfRange(buf, 2, 3);
                                //ByteBuffer wrapped = ByteBuffer.wrap(mifBytes); // big-endian by default
                                //int iifVid = wrapped.getInt();
                                //MulticastRoutingTableInfo iinfo = (MulticastRoutingTableInfo) fds[i].userData;
                                int iifVid = iinfo.mifi;
                                Log.i(TAG, "iifid from userData = " + iifVid);

                                if (iifVid == mrt6Msg.mMif) {

                                    ArrayList<Byte> forwardingOifs = new ArrayList<Byte>();
                                    for (Map.Entry<String, MulticatRoutingConfig> config : iinfo.oifInfo.entrySet()) {
                                        if (config.getValue().mAllowAllAddresses && config.getValue().mMinScope <= dstScope) {
                                            forwardingOifs.add(config.getValue().mifi);
                                        }
                                    }
                                    if (forwardingOifs.size() > 0) {
                                        byte[] oifs = new byte[forwardingOifs.size()];
                                        for (int j = 0; j < forwardingOifs.size(); j++) {
                                            oifs[j] = (byte) forwardingOifs.get(j);
                                            Log.i(TAG, "added oif " + oifs[j]);
                                        }
                                        //oifs[forwardingOifs.size()] = iinfo.mifi;
                                        byte[] anyAddr = new byte[16];
                                        //nativeAddMfc(fds[i].fd, iifVid, oifs, mrt6Msg.mSrc.getAddress(), mrt6Msg.mDst.getAddress());
                                        addMfc(iifVid, oifs, mrt6Msg.mSrc.getAddress(), mrt6Msg.mDst.getAddress(), Instant.now().plusSeconds(1));
                                        addMfc(iifVid, oifs, anyAddr, mrt6Msg.mDst.getAddress(), Instant.now().plusSeconds(300));

                                    }
                                }
                            }
                        } catch (InterruptedIOException e) {
                            Log.i(TAG, "The multicast socket IO thread is interrupted");
                            break;
                        } catch (ErrnoException e) {
                            Log.e(TAG, "Failed to read from multicast socket", e);
                            break;
                        }

                    }
                }
            }
        }
    }

    private void addMfc(int iif, byte[] oifs, byte[] src, byte[] dst, Instant expiresAt) {
        nativeAddMfc(mFD.getFileDescriptor(), iif, oifs, src, dst);
        MulticastForwardingCache mfc = new MulticastForwardingCache(iif, oifs, src, dst, expiresAt);
        mMFCs.add(mfc);

    }

    private void removeMfc(MulticastForwardingCache mfc, Iterator<MulticastForwardingCache> iter) {
        nativeRemoveMfc(mFD.getFileDescriptor(), mfc.mIifVirtualIdx, mfc.mSrcAddr, mfc.mDstAddr);
        iter.remove();
    }
    private void cleanupExpiredMfcs() {
        Iterator<MulticastForwardingCache> iter = mMFCs.iterator();
        Instant now = Instant.now();
        while (iter.hasNext()) {
            MulticastForwardingCache mfc = iter.next();
            if (mfc.mExpiresAt.isBefore(now)) {
                removeMfc(mfc, iter);
            }
        }
    }
    Runnable timeoutChecker = new Runnable() {
        public void run() {
            try {
                cleanupExpiredMfcs();
            } finally{
                mTimeoutHandler.postDelayed(timeoutChecker, 1000);
            }
        }
    };

    private int initSocket() {
        int sock = -1;
        try {
            sock = nativeConfigMulticastSocket();
            Log.i(TAG, "socket created for multicast routing ");
            numOfTables++;
            /*ParcelFileDescriptor fd1 = adoptFd(sock);
            if (fd1 != null) {
                mFileDescriptors.add(fd1);
                Log.i(TAG, "fd1 added to fds");
            }*/
        } catch (IOException e) {
            Log.e(TAG, "failed to create multicast socket: " + e);
        }
        return sock;
    }

    public void EnableMulticastRouting(String iifName,
                                       String oifName,
                                       ArrayList<Inet6Address> aAllowedAddresses,
                                       boolean aAllowAllAddresses,
                                       int minScope) {

        Log.i(TAG, "enable multicast routing from " + iifName + " to " + oifName);
        //isEnabled = true;

        MulticastRoutingTableInfo iifInfo;
        MulticatRoutingConfig oifInfo;

        if (getIifInfo(iifName) == null) {
            iifInfo = new MulticastRoutingTableInfo();
            iifInfo.mifi = getVifIdx(iifName);
            iifInfo.table = Os.if_nametoindex(iifName) + MRT_OFFSET;
            //nativeCreateMrule(oifName, iifInfo.table);
            //NetlinkUtils.sendRtmMruleRequest(iifName, 10000, iifInfo.table);
            //iifInfo.ruleCreated = true;
            //iifInfo.fd = adoptFd(initSocket(iifInfo.table, iifInfo.mifi, iifName));
            //nativeAddMif(mFD.getFileDescriptor(), iifInfo.mifi, iifName);
            mroutingTableInfo.put(iifName, iifInfo);
        }

        iifInfo = getIifInfo(iifName);

        if (iifInfo.getOifInfo(oifName) == null) {
            oifInfo = new MulticatRoutingConfig();
            oifInfo.mifi = getVifIdx(oifName);
            //nativeAddMif(mFD.getFileDescriptor(), oifInfo.mifi, oifName);
            iifInfo.oifInfo.put(oifName, oifInfo);
        }
        oifInfo = iifInfo.getOifInfo(oifName);
        oifInfo.mAllowedAddresses = aAllowedAddresses;
        oifInfo.mAllowAllAddresses = aAllowAllAddresses;
        oifInfo.mMinScope = minScope;

        byte[] anyAddr = new byte[16];
        byte[] oifs = new byte[2];
        oifs[0] = oifInfo.mifi;
        oifs[1] = iifInfo.mifi;
        /*if (oifInfo.mAllowAllAddresses) {
            nativeAddMfc(iifInfo.fd.getFileDescriptor(), oifInfo.mifi, oifs, anyAddr, anyAddr);
        }
        else {*/
        if (!oifInfo.mAllowAllAddresses) {
            for(int x = 0; x < oifInfo.mAllowedAddresses.size(); x++) {
                //nativeAddMfc(iifInfo.fd.getFileDescriptor(), iifInfo.mifi, oifs, anyAddr,
                //        oifInfo.mAllowedAddresses.get(x).getAddress());
                addMfc(iifInfo.mifi, oifs, anyAddr, oifInfo.mAllowedAddresses.get(x).getAddress(), Instant.MAX);
                nativeJoinGroup(mFD.getFileDescriptor(), iifName,
                        oifInfo.mAllowedAddresses.get(x).getAddress());
            }
        }

        if (!threadRunning) {
            mThread = new CoordinatorWorker();
            //mThread.start();
            threadRunning = true;
        }
        
    }

    public void DisableMulticastRouting() {

        Log.i(TAG, "disable multicast routing");
    }

    public boolean isMulticastRoutingEnabled() {
        return isEnabled;
    }

    private static native void nativeCreateMrule(String iifName, int table);
    private static native void nativeHandleMulticastForwardingPkt(byte[] bytes, int iif, int oif, FileDescriptor fd);
    private static native int nativeConfigMulticastSocket() throws IOException;
    private static native void nativeAddMif(FileDescriptor fd, int upstreamVifId, String upstreamIfName);
    private static native void nativeAddMfc(FileDescriptor fd, int iif, byte[] oifs, byte[] src, byte[] dst);

     private static native void nativeRemoveMfc(FileDescriptor fd, int iif, byte[] src, byte[] dst);

     private static native void nativeJoinGroup(FileDescriptor fd, String ifName, byte[] addr);
 }
