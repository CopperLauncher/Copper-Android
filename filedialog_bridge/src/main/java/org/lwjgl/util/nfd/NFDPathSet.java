package org.lwjgl.util.nfd;

/**
 * Compile-time stand-in for the classic-API path set struct that only exists
 * in lwjgl-nfd 3.2.x-3.3.1. Excluded from the built jar (see build.gradle):
 * at runtime the real class always comes from the game's lwjgl-nfd.jar.
 * Only the shape used by NativeFileDialog is provided.
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
