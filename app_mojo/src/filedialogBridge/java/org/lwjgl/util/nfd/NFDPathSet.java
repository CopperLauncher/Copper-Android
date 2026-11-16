package org.lwjgl.util.nfd;

/**
 * Stand-in for the classic-API path set struct that only exists in lwjgl-nfd
 * 3.2.x-3.3.1. Newer lwjgl-nfd releases dropped it, so games without that
 * jar get this class from the launcher bridge. It is only used as an opaque
 * address by the native path set table; no struct fields are read or written.
 */
public class NFDPathSet extends org.lwjgl.system.Struct<NFDPathSet> {
    protected NFDPathSet(long address, java.nio.ByteBuffer container) {
        super(address, container);
    }

    @Override
    protected NFDPathSet create(long address, java.nio.ByteBuffer container) {
        return new NFDPathSet(address, container);
    }

    @Override
    public int sizeof() {
        return 0;
    }
}
