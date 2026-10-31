package codes.castled.gpexpansion.util;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.UUID;

/**
 * Self-contained byte codec for {@link PersistentDataContainer}, used to snapshot and restore
 * sign metadata when moving managed signs.
 *
 * <p>Paper exposes {@code PersistentDataContainer#serializeToBytes()} / {@code #readFromBytes(...)},
 * but the base Spigot API does not, so this helper implements an equivalent binary format that
 * works on every platform. Entries are walked with the standard {@link PersistentDataType}
 * constants (plus Paper-only UUID/NAMESPACED_KEY through reflection when present), including
 * nested containers.
 */
public final class PdcCodec {

    private PdcCodec() {}

    /** Stable wire ids; enacted in fallback order. */
    private static final byte ID_STRING = 1;
    private static final byte ID_BYTE = 2;
    private static final byte ID_SHORT = 3;
    private static final byte ID_INT = 4;
    private static final byte ID_LONG = 5;
    private static final byte ID_FLOAT = 6;
    private static final byte ID_DOUBLE = 7;
    private static final byte ID_BOOLEAN = 8;
    private static final byte ID_BYTE_ARR = 9;
    private static final byte ID_INT_ARR = 10;
    private static final byte ID_LONG_ARR = 11;
    private static final byte ID_NAMESPACED_KEY = 12;
    private static final byte ID_UUID = 13;
    private static final byte ID_TAG_CONTAINER = 14;
    private static final byte ID_TAG_CONTAINER_ARR = 15;

    private static final int MAGIC = 0x47504443; // "GPDC"

    /** Serializes a container to bytes (Paper's serializeToBytes() equivalent). */
    public static byte[] encode(PersistentDataContainer pdc) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            writeContainer(out, pdc);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to encode persistent data container", e);
        }
    }

    /**
     * Deserializes bytes written by {@link #encode(PersistentDataContainer)} into the target
     * container (Paper's readFromBytes(bytes, true) equivalent: existing keys are overwritten).
     */
    public static void decode(byte[] data, PersistentDataContainer target) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            if (in.readInt() != MAGIC) {
                throw new IOException("Not a GPExpansion persistent data snapshot");
            }
            readInto(in, target);
        } catch (ClassCastException | IllegalArgumentException e) {
            throw new IOException("Corrupt persistent data snapshot", e);
        }
    }

    // ============================== encoding ==============================

    private static void writeContainer(DataOutputStream out, PersistentDataContainer pdc) throws IOException {
        java.util.List<NamespacedKey> keys = new java.util.ArrayList<>();
        pdc.getKeys().forEach(keys::add);
        out.writeInt(keys.size());
        for (NamespacedKey key : keys) {
            out.writeUTF(key.getNamespace());
            out.writeUTF(key.getKey());
            if (writeTypedEntry(out, pdc, key)) {
                continue;
            }
            out.writeByte(0);
        }
    }

    /** @return true when a supported type was found and written. */
    private static boolean writeTypedEntry(DataOutputStream out, PersistentDataContainer pdc, NamespacedKey key)
            throws IOException {
        if (pdc.has(key, PersistentDataType.STRING)) {
            out.writeByte(ID_STRING);
            out.writeUTF(pdc.get(key, PersistentDataType.STRING));
            return true;
        }
        if (pdc.has(key, PersistentDataType.BYTE)) {
            out.writeByte(ID_BYTE);
            out.writeByte(pdc.get(key, PersistentDataType.BYTE));
            return true;
        }
        if (pdc.has(key, PersistentDataType.SHORT)) {
            out.writeByte(ID_SHORT);
            out.writeShort(pdc.get(key, PersistentDataType.SHORT));
            return true;
        }
        if (pdc.has(key, PersistentDataType.INTEGER)) {
            out.writeByte(ID_INT);
            out.writeInt(pdc.get(key, PersistentDataType.INTEGER));
            return true;
        }
        if (pdc.has(key, PersistentDataType.LONG)) {
            out.writeByte(ID_LONG);
            out.writeLong(pdc.get(key, PersistentDataType.LONG));
            return true;
        }
        if (pdc.has(key, PersistentDataType.FLOAT)) {
            out.writeByte(ID_FLOAT);
            out.writeFloat(pdc.get(key, PersistentDataType.FLOAT));
            return true;
        }
        if (pdc.has(key, PersistentDataType.DOUBLE)) {
            out.writeByte(ID_DOUBLE);
            out.writeDouble(pdc.get(key, PersistentDataType.DOUBLE));
            return true;
        }
        if (pdc.has(key, PersistentDataType.BOOLEAN)) {
            out.writeByte(ID_BOOLEAN);
            out.writeBoolean(pdc.get(key, PersistentDataType.BOOLEAN));
            return true;
        }
        if (pdc.has(key, PersistentDataType.BYTE_ARRAY)) {
            out.writeByte(ID_BYTE_ARR);
            byte[] arr = pdc.get(key, PersistentDataType.BYTE_ARRAY);
            out.writeInt(arr == null ? 0 : arr.length);
            out.write(arr == null ? new byte[0] : arr);
            return true;
        }
        if (pdc.has(key, PersistentDataType.INTEGER_ARRAY)) {
            out.writeByte(ID_INT_ARR);
            int[] arr = pdc.get(key, PersistentDataType.INTEGER_ARRAY);
            out.writeInt(arr == null ? 0 : arr.length);
            for (int v : arr == null ? new int[0] : arr) {
                out.writeInt(v);
            }
            return true;
        }
        if (pdc.has(key, PersistentDataType.LONG_ARRAY)) {
            out.writeByte(ID_LONG_ARR);
            long[] arr = pdc.get(key, PersistentDataType.LONG_ARRAY);
            out.writeInt(arr == null ? 0 : arr.length);
            for (long v : arr == null ? new long[0] : arr) {
                out.writeLong(v);
            }
            return true;
        }
        if (pdc.has(key, PersistentDataType.TAG_CONTAINER)) {
            out.writeByte(ID_TAG_CONTAINER);
            PersistentDataContainer inner = pdc.get(key, PersistentDataType.TAG_CONTAINER);
            writeContainer(out, inner);
            return true;
        }
        if (pdc.has(key, PersistentDataType.TAG_CONTAINER_ARRAY)) {
            out.writeByte(ID_TAG_CONTAINER_ARR);
            PersistentDataContainer[] arr = pdc.get(key, PersistentDataType.TAG_CONTAINER_ARRAY);
            out.writeInt(arr == null ? 0 : arr.length);
            for (PersistentDataContainer inner : arr == null ? new PersistentDataContainer[0] : arr) {
                writeContainer(out, inner);
            }
            return true;
        }
        // Paper-only types, probed reflectively so the code also compiles against Spigot.
        PersistentDataType<?, ?> nkType = paperType("NAMESPACED_KEY");
        if (nkType != null && pdc.has(key, nkType)) {
            out.writeByte(ID_NAMESPACED_KEY);
            NamespacedKey value = (NamespacedKey) pdc.get(key, nkType);
            out.writeUTF(value.getNamespace());
            out.writeUTF(value.getKey());
            return true;
        }
        PersistentDataType<?, ?> uuidType = paperType("UUID");
        if (uuidType != null && pdc.has(key, uuidType)) {
            out.writeByte(ID_UUID);
            UUID value = (UUID) pdc.get(key, uuidType);
            out.writeLong(value.getMostSignificantBits());
            out.writeLong(value.getLeastSignificantBits());
            return true;
        }
        return false;
    }

    /** Reflects a Paper-only {@link PersistentDataType} constant, null when unavailable. */
    @SuppressWarnings("unchecked")
    private static PersistentDataType<Object, Object> paperType(String name) {
        try {
            Field field = PersistentDataType.class.getField(name);
            return (PersistentDataType<Object, Object>) field.get(null);
        } catch (ReflectiveOperationException | SecurityException | IllegalArgumentException ignored) {
            return null;
        }
    }

    // ============================== decoding ==============================

    private static void readInto(DataInputStream in, PersistentDataContainer target) throws IOException {
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            String namespace = in.readUTF();
            String keyName = in.readUTF();
            NamespacedKey key = new NamespacedKey(namespace, keyName);
            byte typeId = in.readByte();
            if (typeId == 0) {
                continue;
            }
            readTypedEntry(in, target, key, typeId);
        }
    }

    private static void readTypedEntry(DataInputStream in, PersistentDataContainer target, NamespacedKey key,
                                       byte typeId) throws IOException {
        switch (typeId) {
            case ID_STRING -> target.set(key, PersistentDataType.STRING, in.readUTF());
            case ID_BYTE -> target.set(key, PersistentDataType.BYTE, in.readByte());
            case ID_SHORT -> target.set(key, PersistentDataType.SHORT, in.readShort());
            case ID_INT -> target.set(key, PersistentDataType.INTEGER, in.readInt());
            case ID_LONG -> target.set(key, PersistentDataType.LONG, in.readLong());
            case ID_FLOAT -> target.set(key, PersistentDataType.FLOAT, in.readFloat());
            case ID_DOUBLE -> target.set(key, PersistentDataType.DOUBLE, in.readDouble());
            case ID_BOOLEAN -> target.set(key, PersistentDataType.BOOLEAN, in.readBoolean());
            case ID_BYTE_ARR -> {
                byte[] arr = new byte[in.readInt()];
                in.readFully(arr);
                target.set(key, PersistentDataType.BYTE_ARRAY, arr);
            }
            case ID_INT_ARR -> {
                int[] arr = new int[in.readInt()];
                for (int j = 0; j < arr.length; j++) {
                    arr[j] = in.readInt();
                }
                target.set(key, PersistentDataType.INTEGER_ARRAY, arr);
            }
            case ID_LONG_ARR -> {
                long[] arr = new long[in.readInt()];
                for (int j = 0; j < arr.length; j++) {
                    arr[j] = in.readLong();
                }
                target.set(key, PersistentDataType.LONG_ARRAY, arr);
            }
            case ID_NAMESPACED_KEY -> {
                PersistentDataType<?, ?> t = paperType("NAMESPACED_KEY");
                if (t == null) {
                    in.readUTF();
                    in.readUTF();
                    return;
                }
                @SuppressWarnings("unchecked")
                PersistentDataType<NamespacedKey, NamespacedKey> nkType = (PersistentDataType<NamespacedKey, NamespacedKey>) t;
                target.set(key, nkType, new NamespacedKey(in.readUTF(), in.readUTF()));
            }
            case ID_UUID -> {
                PersistentDataType<?, ?> t = paperType("UUID");
                if (t == null) {
                    in.readLong();
                    in.readLong();
                    return;
                }
                @SuppressWarnings("unchecked")
                PersistentDataType<UUID, UUID> uuidType = (PersistentDataType<UUID, UUID>) t;
                target.set(key, uuidType, new UUID(in.readLong(), in.readLong()));
            }
            case ID_TAG_CONTAINER -> {
                PersistentDataContainer inner = target.getAdapterContext().newPersistentDataContainer();
                readInto(in, inner);
                target.set(key, PersistentDataType.TAG_CONTAINER, inner);
            }
            case ID_TAG_CONTAINER_ARR -> {
                int len = in.readInt();
                PersistentDataContainer[] arr = new PersistentDataContainer[Math.max(0, len)];
                for (int j = 0; j < arr.length; j++) {
                    PersistentDataContainer inner = target.getAdapterContext().newPersistentDataContainer();
                    readInto(in, inner);
                    arr[j] = inner;
                }
                target.set(key, PersistentDataType.TAG_CONTAINER_ARRAY, arr);
            }
            default -> throw new IOException("Unsupported type id " + typeId);
        }
    }
}
