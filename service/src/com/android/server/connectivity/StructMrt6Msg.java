package com.android.server.connectivity;

import java.net.Inet6Address;

import com.android.net.module.util.Struct;

public class StructMrt6Msg extends Struct {
    public static final int STRUCT_SIZE = 40;
    public static final int MRT6MSG_NOCACHE = 1;

    @Field(order = 0, type = Type.S8)
    public final byte mbz; // must be zero
    @Field(order = 1, type = Type.S8)
    public final byte msgType; // message type
    @Field(order = 2, type = Type.UBE16)
    public final int mif; // mif received on
    @Field(order = 3, type = Type.U32)
    public final long padding;
    @Field(order = 4, type = Type.Ipv6Address)
    public final Inet6Address src;
    @Field(order = 5, type = Type.Ipv6Address)
    public final Inet6Address dst;

    StructMrt6Msg(final byte mbz, final byte msgType, final int mif, final long pad,
                  final Inet6Address source, final Inet6Address destination) {
        this.mbz = mbz;
        this.msgType = msgType;
        this.mif = (int) Short.reverseBytes(((short) mif));
        this.padding = pad;
        this.src = source;
        this.dst = destination;
    }
}

