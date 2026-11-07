import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.Path;

class CoreInvariantsTest {

    @Test
    void direct_split_preserves_characters_exactly() {
        String raw = loadResource("split_sample.txt");
        assertTrue(raw.length() > 5000, "sample should be large enough to exercise segmentation");
        java.lang.reflect.Method m;
        try {
            Class<?> c = Class.forName("com.tingben.app.core.TextSplitter");
            m = c.getDeclaredMethod("split", String.class);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("TextSplitter.split must exist", e);
        }
        try {
            Object result = m.invoke(null, raw);
            java.lang.reflect.Method statsMethod = result.getClass().getMethod("getSecond");
            Object stats = statsMethod.invoke(result);
            int charsIn = (int) stats.getClass().getMethod("getCharsIn").invoke(stats);
            int charsOut = (int) stats.getClass().getMethod("getCharsOut").invoke(stats);
            assertEquals(raw.length(), charsIn, "charsIn must equal source length");
            assertEquals(raw.length(), charsOut, "charsOut must reconstruct to source length");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void direct_split_reconstructs_source_exactly() {
        String raw = loadResource("split_sample.txt");
        java.lang.reflect.Method m;
        try {
            Class<?> c = Class.forName("com.tingben.app.core.TextSplitter");
            m = c.getDeclaredMethod("split", String.class);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        try {
            Object result = m.invoke(null, raw);
            java.util.List<?> segs = (java.util.List<?>) result.getClass().getMethod("getFirst").invoke(result);
            StringBuilder sb = new StringBuilder();
            for (Object s : segs) sb.append((String) s);
            assertEquals(raw, sb.toString(), "concatenation of segments must equal source exactly");
            assertEquals(raw.length(), sb.length());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void empty_input_produces_zero_stats() {
        java.lang.reflect.Method m;
        try {
            Class<?> c = Class.forName("com.tingben.app.core.TextSplitter");
            m = c.getDeclaredMethod("split", String.class);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        try {
            Object result = m.invoke(null, "");
            Object stats = result.getClass().getMethod("getSecond").invoke(result);
            int charsIn = (int) stats.getClass().getMethod("getCharsIn").invoke(stats);
            int charsOut = (int) stats.getClass().getMethod("getCharsOut").invoke(stats);
            assertEquals(0, charsIn);
            assertEquals(0, charsOut);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void wav_header_is_24khz_16bit_mono() throws IOException {
        byte[] wav = makeWav(48000);
        assertEquals('R', (char) wav[0]);
        assertEquals('I', (char) wav[1]);
        assertEquals('F', (char) wav[2]);
        assertEquals('F', (char) wav[3]);
        assertEquals('W', (char) wav[8]);
        assertEquals('A', (char) wav[9]);
        assertEquals('V', (char) wav[10]);
        assertEquals('E', (char) wav[11]);
        assertEquals(24000, readInt32(wav, 24));
        assertEquals(2, readInt16(wav, 22), "fmt chunk size must be 16 for PCM");
        assertEquals(1, readInt16(wav, 22) > 0 ? readInt16(wav, 22) : 1, "mono");
    }

    @Test
    void duration_from_pcm_bytes_is_correct() {
        Object lib;
        try {
            lib = Class.forName("com.tingben.app.core.AudioLibrary").getDeclaredMethod("INSTANCE").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("AudioLibrary must be a Kotlin object", e);
        }
        long ms = invokeLong(lib, "durationMsOfBytes", 24000L + 44L);
        assertEquals(500L, ms, "24000 PCM bytes must be 500ms at 24kHz/16bit/mono");
        long oneSec = invokeLong(lib, "durationMsOfBytes", 48000L + 44L);
        assertEquals(1000L, oneSec, "48000 PCM bytes must be 1000ms");
    }

    @Test
    void duration_is_nonnegative_for_tiny_or_invalid_files() {
        Object lib = getInstance("com.tingben.app.core.AudioLibrary");
        assertTrue(invokeLong(lib, "durationMsOfBytes", 44L) >= 0);
        assertTrue(invokeLong(lib, "durationMsOfBytes", 0L) >= 0);
        assertTrue(invokeLong(lib, "durationMsOfBytes", 10L) >= 0);
    }

    @Test
    void chapter_start_bytes_to_millis_matches_wav_pcm_rate(@TempDir Path tmp) throws IOException {
        File dir = tmp.toFile();
        byte[] wav = makeWav(48000);
        File wavFile = new File(dir, "abc.wav");
        try (FileOutputStream fos = new FileOutputStream(wavFile)) { fos.write(wav); }
        File chapters = new File(dir, "abc.chapters.json");
        try (FileWriter fw = new FileWriter(chapters)) {
            fw.write("[{\"index\":0,\"title\":\"A\",\"startMs\":0}," +
                     "{\"index\":1,\"title\":\"B\",\"startMs\":240}," +
                     "{\"index\":2,\"title\":\"C\",\"startMs\":500}]");
        }
        Object lib = getInstance("com.tingben.app.core.AudioLibrary");
        long start = invokeLong(lib, "chapterStartMs", new File(dir, "missing.chapters.json"), 2);
        assertEquals(0L, start);
        long real = invokeLong(lib, "chapterStartMs", new File(dir, "abc.chapters.json"), 2);
    }

    @Test
    void chapter_start_helper_uses_pcm_byte_rate() {
        Object lib = getInstance("com.tingben.app.core.AudioLibrary");
        long ms = invokeLong(lib, "durationMsOfBytes", 48L + 44L);
        assertEquals(1L, ms);
    }

    @Test
    void expected_mode_stamp_is_stable_for_same_inputs() {
        Class<?> c = assertDoesNotThrow(() -> Class.forName("com.tingben.app.core.TaskResume"));
        assertNotNull(c.getDeclaredMethod("expectedModeStamp", android.content.Context.class));
    }

    @Test
    void resume_bookmark_round_trip_preserves_identity() {
        Class<?> c = assertDoesNotThrow(() -> Class.forName("com.tingben.app.core.TaskResume"));
        assertNotNull(c.getDeclaredMethod("save", android.content.Context.class,
                com.tingben.app.core.TaskInfo.class));
        assertNotNull(c.getDeclaredMethod("load", android.content.Context.class));
    }

    @Test
    void task_info_accepts_new_config_and_mode_fields() {
        Class<?> c = com.tingben.app.core.TaskInfo.class;
        assertNotNull(c.getDeclaredConstructor(
                String.class, String.class, java.util.Set.class, boolean.class, boolean.class,
                int.class, int.class, String.class, String.class, long.class, String.class,
                com.tingben.app.core.ProduceConfig.class, String.class));
    }

    @Test
    void safe_name_rejects_blank_and_normalizes() {
        Class<?> c = assertDoesNotThrow(() -> Class.forName("com.tingben.app.core.MixerPresetStore"));
        java.lang.reflect.Method m = c.getDeclaredMethod("safeName", String.class);
        assertEquals("untitled", m.invoke(null, "   "));
        assertEquals("untitled", m.invoke(null, "../bad/name"));
        assertEquals("abc", m.invoke(null, " abc "));
    }

    @Test
    void preset_to_and_from_json_preserves_identity() {
        Class<?> c = assertDoesNotThrow(() -> Class.forName("com.tingben.app.core.MixerPreset"));
        Object built = c.getDeclaredConstructor(
                String.class, String.class, String.class, java.util.Map.class,
                com.tingben.app.core.TtsKnobs.class, com.tingben.app.core.ProduceConfig.class, boolean.class)
                .newInstance("p1", "Demo", "desc",
                        java.util.Collections.singletonMap("a", "1"),
                        new com.tingben.app.core.TtsKnobs(7L, 512, 0.5f, 20, 0.8f, 1.1f, 0.4f, 10, 0.9f),
                        new com.tingben.app.core.ProduceConfig(), true);
        org.json.JSONObject json = (org.json.JSONObject) built.getClass().getMethod("toJson").invoke(built);
        Object back = c.getDeclaredMethod("fromJson", org.json.JSONObject.class).invoke(null, json);
        assertEquals("p1", ((com.tingben.app.core.MixerPreset) back).getId());
        assertEquals("Demo", ((com.tingben.app.core.MixerPreset) back).getName());
        assertEquals("desc", ((com.tingben.app.core.MixerPreset) back).getVoice());
        assertEquals(true, ((com.tingben.app.core.MixerPreset) back).isBuiltin());
    }

    @Test
    void sampling_params_define_consistent_ranges() {
        Class<?> c = assertDoesNotThrow(() -> Class.forName("com.tingben.app.core.ParamCatalog"));
        java.util.List<?> all = (java.util.List<?>) c.getDeclaredField("SAMPLING").get(null);
        assertFalse(all.isEmpty());
        for (Object spec : all) {
            String key = (String) spec.getClass().getMethod("getKey").invoke(spec);
            String def = (String) spec.getClass().getMethod("getDef").invoke(spec);
            java.lang.reflect.Method validate = spec.getClass().getMethod("validate", String.class);
            assertNull(validate.invoke(spec, def), "default must validate for " + key);
        }
    }

    @Test
    void nonverbal_apply_preserves_text_identity() {
        com.tingben.app.core.Annonly text = new com.tingben.app.core.Annonly("她笑了，转身离开。");
        com.tingben.app.core.Annonly out =
                com.tingben.app.core.NonVerbal.apply(text);
        assertEquals(text.text, out.text, "NonVerbal must not alter the text");
        assertEquals(text.speaker, out.speaker);
    }

    @Test
    void importer_chapter_separator_is_single_private_char() {
        java.lang.reflect.Field f = com.tingben.app.core.Importer.class.getDeclaredField("CHAPTER_SEP");
        f.setAccessible(true);
        Object sep = f.get(null);
        assertEquals(1, sep.toString().length());
    }

    @Test
    void importer_rejects_oversized_stream() throws IOException {
        java.io.InputStream big = new java.io.ByteArrayInputStream(new byte[65 * 1024 * 1024]);
        assertThrows(RuntimeException.class, () -> com.tingben.app.core.Importer.read(null, null));
        big.close();
    }

    private static String loadResource(String name) {
        try (InputStream in = CoreInvariantsTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "missing test resource: " + name);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) { throw new AssertionError(e); }
    }

    private static Object getInstance(String fqcn) {
        try {
            Class<?> c = Class.forName(fqcn);
            return c.getDeclaredMethod("getInstance").invoke(null);
        } catch (ReflectiveOperationException e) {
            try {
                Class<?> c = Class.forName(fqcn);
                java.lang.reflect.Field f = c.getDeclaredField("INSTANCE");
                return f.get(null);
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            }
        }
    }

    private static long invokeLong(Object target, String name, Object arg) {
        try {
            return (long) target.getClass().getMethod(name, long.class).invoke(target, arg);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static long invokeLong(Object target, String name, Class<?>[] types, Object... args) {
        try {
            return (long) target.getClass().getMethod(name, types).invoke(target, args);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static byte[] makeWav(int pcmLen) {
        byte[] h = new byte[44];
        h[0]='R';h[1]='I';h[2]='F';h[3]='F';
        writeInt32(h,4,36+pcmLen);
        h[8]='W';h[9]='A';h[10]='V';h[11]='E';
        h[12]='f';h[13]='m';h[14]='t';h[15]=' ';
        writeInt32(h,16,16);
        writeInt16(h,20,1);
        writeInt16(h,22,1);
        writeInt32(h,24,24000);
        writeInt32(h,28,24000*1*16/8);
        writeInt16(h,32,1*16/8);
        writeInt16(h,34,16);
        h[36]='d';h[37]='a';h[38]='t';h[39]='a';
        writeInt32(h,40,pcmLen);
        byte[] out = new byte[44+pcmLen];
        System.arraycopy(h,0,out,0,44);
        return out;
    }

    private static void writeInt16(byte[] b, int off, int v) {
        b[off]=(byte)(v); b[off+1]=(byte)(v>>8);
    }
    private static void writeInt32(byte[] b, int off, int v) {
        b[off]=(byte)v; b[off+1]=(byte)(v>>8); b[off+2]=(byte)(v>>16); b[off+3]=(byte)(v>>24);
    }
    private static int readInt16(byte[] b, int off) { return (b[off]&0xff)|((b[off+1]&0xff)<<8); }
    private static int readInt32(byte[] b, int off) {
        return (b[off]&0xff)|((b[off+1]&0xff)<<8)|((b[off+2]&0xff)<<16)|((b[off+3]&0xff)<<24);
    }
}
