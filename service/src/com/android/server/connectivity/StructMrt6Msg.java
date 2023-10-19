package com.android.server.connectivity;

import java.net.Inet6Address;

import com.android.net.module.util.Struct;

public class StructMrt6Msg extends Struct {
    public static final int STRUCT_SIZE = 40;

    @Field(order = 0, type = Type.S8)
    public final byte mMbz;
    @Field(order = 1, type = Type.S8)
    public final byte mMsgtype;
    @Field(order = 2, type = Type.UBE16)
    public final int mMif;
    @Field(order = 3, type = Type.U32)
    public final long mPadding;
    @Field(order = 4, type = Type.Ipv6Address)
    public final Inet6Address mSrc;
    @Field(order = 5, type = Type.Ipv6Address)
    public final Inet6Address mDst;

    StructMrt6Msg(final byte mbz, final byte msgType, final int mif, final long pad, final Inet6Address source, final Inet6Address destination) {
        mMbz = mbz;
        mMsgtype = msgType;
        mMif = (int) Short.reverseBytes(((short) mif));
        mPadding = pad;
        mSrc = source;
        mDst = destination;
    }
}

