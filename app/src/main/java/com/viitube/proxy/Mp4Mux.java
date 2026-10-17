package com.viitube.proxy;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * Experimental on-the-fly muxer for YouTube's adaptive streams (no transcoding).
 *
 * <p>YouTube serves adaptive MP4 streams as fragmented MP4: an init segment (ftyp + moov,
 * "initRange"), an index (sidx, "indexRange") and moof/mdat fragments. Like the DASH manifest
 * Invidious builds from the same "initRange"/"indexRange" fields, the index tells where every
 * fragment is. A video-only stream and an audio stream are combined into one fragmented MP4:
 * the two moov boxes are merged into one with two tracks, and the fragments of both are sent
 * in time order with only their track and sequence numbers rewritten. Since every byte count
 * is known up front, the output has an exact Content-Length and supports Range requests.
 */
final class Mp4Mux {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Safari/537.36";
    /** Source runs (about 5 s of video or 10 s of audio each) downloaded ahead of the player. */
    private static final int PREFETCH_RUNS = 6;
    /** Classic output interleaves audio and video about this often, like YouTube's own MP4s. */
    private static final double CHUNK_SECONDS = 0.5;

    private Mp4Mux() { }

    /** One fragment (moof + mdat) of an input stream, in output order. */
    static final class Piece {
        final int track;          // 1 = video, 2 = audio
        final String url;
        final long offset;        // in the source stream
        final int size;
        final double start;       // seconds
        long outputOffset;
        int sequence;
        /** Fragmented output: rewrite moof track/sequence numbers. Classic output: raw samples. */
        boolean fragment = true;
        /** Source byte range downloaded in one request; this piece is a part of it. */
        long runOffset;
        int runSize;

        Piece(int track, String url, long offset, int size, double start) {
            this.track = track;
            this.url = url;
            this.offset = offset;
            this.size = size;
            this.start = start;
            this.runOffset = offset;
            this.runSize = size;
        }
    }

    /** Everything needed to produce the muxed file. */
    static final class Plan {
        byte[] init;
        final List<Piece> pieces = new ArrayList<Piece>();
        long length;
        double durationSeconds;
        boolean classic;
        long createdAt = System.currentTimeMillis();
    }

    private static final class Source {
        String url;
        byte[] moov;
        byte[] ftyp;
        final List<Piece> pieces = new ArrayList<Piece>();
        double duration;
        long movieTimescale;
        long mediaTimescale;
    }

    /** Above this many fragments, reading every fragment header would delay playback too much. */
    private static final int CLASSIC_MAX_FRAGMENTS = 480;

    /**
     * Classic MP4 (one moov with complete sample tables, then one mdat) when the video is short
     * enough to read all fragment headers first; it plays on every Android version and seeks
     * by index. Longer videos use the fragmented layout.
     */
    static Plan prepareBest(JSONObject videoFormat, JSONObject audioFormat, String videoUrl,
                            String audioUrl) throws IOException {
        Source video = loadSource(videoFormat, videoUrl, 1);
        Source audio = loadSource(audioFormat, audioUrl, 2);
        if (video.pieces.size() + audio.pieces.size() <= CLASSIC_MAX_FRAGMENTS) {
            // No fragmented fallback here: Samsung's Android 4.4 player crashes on it.
            return prepareClassic(video, audio);
        }
        return prepareFragmented(video, audio);
    }

    /** Builds the output layout from the two adaptive formats of a player response. */
    static Plan prepare(JSONObject videoFormat, JSONObject audioFormat, String videoUrl,
                        String audioUrl) throws IOException {
        return prepareFragmented(loadSource(videoFormat, videoUrl, 1),
                loadSource(audioFormat, audioUrl, 2));
    }

    private static Plan prepareFragmented(Source video, Source audio) throws IOException {
        Plan plan = new Plan();
        plan.durationSeconds = Math.max(video.duration, audio.duration);
        plan.init = buildInit(video, audio, plan.durationSeconds);

        plan.pieces.addAll(video.pieces);
        plan.pieces.addAll(audio.pieces);
        // Time order keeps audio and video close together, so playback can start at once.
        sortByStart(plan.pieces);
        long offset = plan.init.length;
        int sequence = 1;
        for (Piece piece : plan.pieces) {
            piece.outputOffset = offset;
            piece.sequence = sequence++;
            offset += piece.size;
        }
        plan.length = offset;
        return plan;
    }

    @SuppressWarnings("unchecked")
    private static void sortByStart(List<Piece> pieces) {
        Collections.sort(pieces, new ByStart());
    }

    private static Source loadSource(JSONObject format, String url, int track) throws IOException {
        JSONObject initRange = format.optJSONObject("initRange");
        JSONObject indexRange = format.optJSONObject("indexRange");
        if (initRange == null || indexRange == null) {
            throw new IOException("format " + format.optInt("itag") + " has no init/index range");
        }
        long initStart = Long.parseLong(initRange.optString("start", "0"));
        long initEnd = Long.parseLong(initRange.optString("end", "0"));
        long indexStart = Long.parseLong(indexRange.optString("start", "0"));
        long indexEnd = Long.parseLong(indexRange.optString("end", "0"));
        if (initStart != 0 || indexStart != initEnd + 1) {
            throw new IOException("unexpected layout of format " + format.optInt("itag"));
        }
        // init and index are adjacent: one request for both
        byte[] head = HttpClient.getRange(url, 0, indexEnd, USER_AGENT);

        Source source = new Source();
        source.url = url;
        int[] ftyp = findBox(head, 0, (int) initEnd + 1, "ftyp");
        int[] moov = findBox(head, 0, (int) initEnd + 1, "moov");
        if (moov == null) throw new IOException("no moov in format " + format.optInt("itag"));
        source.ftyp = ftyp == null ? null : slice(head, ftyp[0], ftyp[1]);
        source.moov = slice(head, moov[0], moov[1]);
        int[] mvhd = findBox(source.moov, 8, source.moov.length, "mvhd");
        if (mvhd == null) throw new IOException("no mvhd");
        source.movieTimescale = u32(source.moov, mvhd[0] + 8 + 4 + (source.moov[mvhd[0] + 8] == 1 ? 16 : 8));
        int[] mdhd = findPath(source.moov, "trak", "mdia", "mdhd");
        if (mdhd == null) throw new IOException("no mdhd");
        source.mediaTimescale = u32(source.moov, mdhd[0] + 8 + 4 + (source.moov[mdhd[0] + 8] == 1 ? 16 : 8));

        int[] sidx = findBox(head, (int) indexStart, head.length, "sidx");
        if (sidx == null) throw new IOException("no sidx in format " + format.optInt("itag"));
        // Fragment offsets in sidx count from the first byte after the sidx box.
        readIndex(head, sidx[0], sidx[1], sidx[0] + sidx[1], source, track);
        return source;
    }

    /** Reads the fragment list of a sidx box; offsets are relative to the byte after it. */
    private static void readIndex(byte[] data, int box, int boxSize, long anchor, Source source,
                                  int track) throws IOException {
        int p = box + 8;
        int version = data[p] & 0xff;
        p += 4;                           // version + flags
        p += 4;                           // reference_ID
        long timescale = u32(data, p);
        p += 4;
        long earliest;
        long firstOffset;
        if (version == 0) {
            earliest = u32(data, p);
            firstOffset = u32(data, p + 4);
            p += 8;
        } else {
            earliest = u64(data, p);
            firstOffset = u64(data, p + 8);
            p += 16;
        }
        p += 2;                           // reserved
        int count = ((data[p] & 0xff) << 8) | (data[p + 1] & 0xff);
        p += 2;
        long offset = anchor + firstOffset;
        long time = earliest;
        for (int i = 0; i < count; i++) {
            long reference = u32(data, p);
            long duration = u32(data, p + 4);
            p += 12;
            if ((reference & 0x80000000L) != 0) throw new IOException("nested sidx is not supported");
            int size = (int) (reference & 0x7fffffffL);
            source.pieces.add(new Piece(track, source.url, offset, size, time / (double) timescale));
            offset += size;
            time += duration;
        }
        source.duration = time / (double) timescale;
        if (p > box + boxSize) throw new IOException("corrupt sidx");
    }

    /** ftyp + one moov with the video track (id 1), the audio track (id 2) and mvex. */
    private static byte[] buildInit(Source video, Source audio, double duration) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        if (video.ftyp != null) out.write(video.ftyp);

        byte[] mvhd = boxCopy(video.moov, "mvhd");
        int version = mvhd[8] & 0xff;
        long movieDuration = Math.round(duration * video.movieTimescale);
        int durationOffset = 8 + 4 + (version == 1 ? 16 : 8) + 4;
        writeDuration(mvhd, durationOffset, version, movieDuration);
        putU32(mvhd, mvhd.length - 4, 3); // next_track_ID

        byte[] videoTrak = patchTrak(boxCopy(video.moov, "trak"), 1, movieDuration,
                Math.round(video.duration * video.mediaTimescale), 1.0);
        byte[] audioTrak = patchTrak(boxCopy(audio.moov, "trak"), 2,
                Math.round(audio.duration * video.movieTimescale),
                Math.round(audio.duration * audio.mediaTimescale),
                audio.movieTimescale == 0 ? 1.0 : video.movieTimescale / (double) audio.movieTimescale);

        ByteArrayOutputStream mvex = new ByteArrayOutputStream();
        byte[] mehd = new byte[8 + 4 + 8];  // version 1: 64-bit fragment_duration
        header(mehd, "mehd");
        mehd[8] = 1;
        putU64(mehd, 12, movieDuration);
        mvex.write(mehd);
        mvex.write(trex(video.moov, 1));
        mvex.write(trex(audio.moov, 2));

        ByteArrayOutputStream moovBody = new ByteArrayOutputStream();
        moovBody.write(mvhd);
        moovBody.write(videoTrak);
        moovBody.write(audioTrak);
        moovBody.write(wrap("mvex", mvex.toByteArray()));
        out.write(wrap("moov", moovBody.toByteArray()));
        return out.toByteArray();
    }

    /**
     * Sets the track id and durations of a trak. {@code timescaleFactor} converts edit list
     * durations, which use the source file's movie timescale, to the output's.
     */
    private static byte[] patchTrak(byte[] trak, int trackId, long movieDuration, long mediaDuration,
                                    double timescaleFactor) throws IOException {
        int[] tkhd = findBox(trak, 8, trak.length, "tkhd");
        if (tkhd == null) throw new IOException("no tkhd");
        int version = trak[tkhd[0] + 8] & 0xff;
        int idOffset = tkhd[0] + 8 + 4 + (version == 1 ? 16 : 8);
        putU32(trak, idOffset, trackId);
        writeDuration(trak, idOffset + 8, version, movieDuration);
        int[] mdhd = findPath(trak, "mdia", "mdhd");
        if (mdhd != null) {
            int mdhdVersion = trak[mdhd[0] + 8] & 0xff;
            writeDuration(trak, mdhd[0] + 8 + 4 + (mdhdVersion == 1 ? 16 : 8) + 4, mdhdVersion,
                    mediaDuration);
        }
        int[] elst = findPath(trak, "edts", "elst");
        if (elst != null && timescaleFactor != 1.0) {
            int elstVersion = trak[elst[0] + 8] & 0xff;
            int count = (int) u32(trak, elst[0] + 12);
            int entry = elst[0] + 16;
            int entrySize = elstVersion == 1 ? 20 : 12;
            for (int i = 0; i < count && entry + entrySize <= elst[0] + elst[1]; i++) {
                long segment = elstVersion == 1 ? u64(trak, entry) : u32(trak, entry);
                writeDuration(trak, entry, elstVersion, Math.round(segment * timescaleFactor));
                entry += entrySize;
            }
        }
        return trak;
    }

    private static byte[] trex(byte[] moov, int trackId) throws IOException {
        int[] trex = findPath(moov, "mvex", "trex");
        byte[] box;
        if (trex != null) {
            box = slice(moov, trex[0], trex[1]);
        } else {
            box = new byte[32];
            header(box, "trex");
            putU32(box, 20, 1);           // default_sample_description_index
        }
        putU32(box, 12, trackId);
        return box;
    }

    /** Rewrites the sequence and track numbers of every moof in a fragment, in place. */
    static void patchFragment(byte[] data, int sequence, int trackId) throws IOException {
        int p = 0;
        while (p + 8 <= data.length) {
            long size = u32(data, p);
            String type = type(data, p + 4);
            if (size < 8 || p + size > data.length) break;
            if ("moof".equals(type)) {
                int end = (int) (p + size);
                int q = p + 8;
                while (q + 8 <= end) {
                    long childSize = u32(data, q);
                    String child = type(data, q + 4);
                    if (childSize < 8) break;
                    if ("mfhd".equals(child)) {
                        putU32(data, q + 12, sequence);
                    } else if ("traf".equals(child)) {
                        int[] tfhd = findBox(data, q + 8, (int) (q + childSize), "tfhd");
                        if (tfhd != null) putU32(data, tfhd[0] + 12, trackId);
                    }
                    q += childSize;
                }
            }
            p += size;
        }
    }

    // --- classic (non-fragmented) layout --------------------------------------------------

    /** Growable int list, to keep per-sample tables small on old phones. */
    private static final class Ints {
        int[] values = new int[256];
        int size;

        void add(int value) {
            if (size == values.length) values = java.util.Arrays.copyOf(values, size * 2);
            values[size++] = value;
        }
    }

    /** Sample tables of one track, rebuilt from its fragment headers. */
    private static final class Samples {
        final Ints sizes = new Ints();
        final Ints durations = new Ints();
        final Ints offsets = new Ints();      // composition offsets
        final Ints syncSamples = new Ints();  // 1-based
        final Ints chunkSampleCounts = new Ints();
        final List<Piece> chunks = new ArrayList<Piece>();
        final Longs sampleOffsets = new Longs();
        final Longs decodeTimes = new Longs();
        final Ints sampleRuns = new Ints();
        final List<long[]> runs = new ArrayList<long[]>();  // {offset, size}
        boolean hasCompositionOffsets;
        long mediaDuration;
    }

    private static final class Longs {
        long[] values = new long[256];
        int size;

        void add(long value) {
            if (size == values.length) values = java.util.Arrays.copyOf(values, size * 2);
            values[size++] = value;
        }
    }

    /**
     * Splits a track into chunks of about {@link #CHUNK_SECONDS}. A chunk never crosses a
     * source run, so its bytes are one contiguous part of a downloaded run.
     */
    private static void buildChunks(Samples samples, long timescale, String url, int track) {
        long target = Math.max(1, Math.round(CHUNK_SECONDS * timescale));
        int i = 0;
        while (i < samples.sizes.size) {
            int run = samples.sampleRuns.values[i];
            long start = samples.decodeTimes.values[i];
            long bytes = 0;
            int count = 0;
            while (i < samples.sizes.size && samples.sampleRuns.values[i] == run
                    && (count == 0 || samples.decodeTimes.values[i] - start < target)) {
                bytes += samples.sizes.values[i] & 0xffffffffL;
                count++;
                i++;
            }
            long[] source = samples.runs.get(run);
            Piece chunk = new Piece(track, url, samples.sampleOffsets.values[i - count], (int) bytes,
                    start / (double) timescale);
            chunk.fragment = false;
            chunk.runOffset = source[0];
            chunk.runSize = (int) source[1];
            samples.chunks.add(chunk);
            samples.chunkSampleCounts.add(count);
        }
    }

    private static final ExecutorService HEADERS = Executors.newFixedThreadPool(8, new DownloadThreads());

    private static Plan prepareClassic(Source video, Source audio) throws IOException {
        long started = System.currentTimeMillis();
        // Queue the headers of both tracks before waiting for any of them.
        List<Future<byte[]>> videoHeaders = requestHeaders(video);
        List<Future<byte[]>> audioHeaders = requestHeaders(audio);
        Samples videoSamples = readSamples(video, videoHeaders, true);
        Samples audioSamples = readSamples(audio, audioHeaders, false);
        android.util.Log.i("Mp4Mux", "Read " + (video.pieces.size() + audio.pieces.size())
                + " fragment headers in " + (System.currentTimeMillis() - started) + "ms");

        Plan plan = new Plan();
        plan.classic = true;
        plan.durationSeconds = Math.max(videoSamples.mediaDuration / (double) video.mediaTimescale,
                audioSamples.mediaDuration / (double) audio.mediaTimescale);
        plan.pieces.addAll(videoSamples.chunks);
        plan.pieces.addAll(audioSamples.chunks);
        sortByStart(plan.pieces);
        long payload = 0;
        for (Piece piece : plan.pieces) payload += piece.size;
        boolean large = payload > 0xF0000000L;

        byte[] ftyp = classicFtyp();
        // First pass sizes the moov; offsets only change values, not sizes.
        byte[] moov = classicMoov(video, audio, videoSamples, audioSamples, plan.durationSeconds, large);
        long offset = ftyp.length + moov.length + (large ? 16 : 8);
        for (Piece piece : plan.pieces) {
            piece.outputOffset = offset;
            offset += piece.size;
        }
        moov = classicMoov(video, audio, videoSamples, audioSamples, plan.durationSeconds, large);

        ByteArrayOutputStream init = new ByteArrayOutputStream(ftyp.length + moov.length + 16);
        init.write(ftyp);
        init.write(moov);
        byte[] mdat = new byte[large ? 16 : 8];
        if (large) {
            putU32(mdat, 0, 1);
            putU64(mdat, 8, payload + 16);
        } else {
            putU32(mdat, 0, payload + 8);
        }
        mdat[4] = 'm'; mdat[5] = 'd'; mdat[6] = 'a'; mdat[7] = 't';
        init.write(mdat);
        plan.init = init.toByteArray();
        // Chunk output offsets were assigned after the header, before the moov's final pass.
        plan.length = plan.init.length + payload;
        return plan;
    }

    private static byte[] classicFtyp() {
        byte[] ftyp = new byte[32];
        header(ftyp, "ftyp");
        String brands = "isom" + "\0\0\2\0" + "isomiso2avc1mp41";
        for (int i = 0; i < brands.length(); i++) ftyp[8 + i] = (byte) brands.charAt(i);
        return ftyp;
    }

    /** Builds the sample tables of a track from its fragment headers (downloaded in parallel). */
    private static List<Future<byte[]>> requestHeaders(Source source) {
        List<Future<byte[]>> headers = new ArrayList<Future<byte[]>>();
        for (Piece piece : source.pieces) headers.add(HEADERS.submit(new HeaderDownload(piece)));
        return headers;
    }

    private static Samples readSamples(Source source, List<Future<byte[]>> headers, boolean video)
            throws IOException {
        long[] defaults = trexDefaults(source.moov);
        Samples samples = new Samples();
        try {
            for (int i = 0; i < source.pieces.size(); i++) {
                byte[] moof;
                try {
                    moof = headers.get(i).get();
                } catch (Exception e) {
                    throw new IOException("fragment header " + i + ": " + e);
                }
                readMoof(moof, source.pieces.get(i), defaults, source.mediaTimescale, video, samples);
            }
            buildChunks(samples, source.mediaTimescale, source.url, source.pieces.get(0).track);
        } finally {
            for (Future<byte[]> future : headers) future.cancel(true);
        }
        return samples;
    }

    /** First bytes of a fragment, enough for its moof box. */
    private static final class HeaderDownload implements java.util.concurrent.Callable<byte[]> {
        private final Piece piece;

        HeaderDownload(Piece piece) {
            this.piece = piece;
        }

        @Override
        public byte[] call() throws Exception {
            int guess = Math.min(piece.size, 16384);
            byte[] head = HttpClient.getRange(piece.url, piece.offset, piece.offset + guess - 1, USER_AGENT);
            int[] moof = findBox(head, 0, head.length, "moof");
            if (moof != null) return slice(head, moof[0], moof[1]);
            long size = u32(head, 0);
            if (!"moof".equals(type(head, 4)) || size > piece.size) throw new IOException("no moof");
            return HttpClient.getRange(piece.url, piece.offset, piece.offset + size - 1, USER_AGENT);
        }
    }

    /** {default duration, default size, default flags} from the source trex. */
    private static long[] trexDefaults(byte[] moov) {
        int[] trex = findPath(moov, "mvex", "trex");
        if (trex == null) return new long[]{0, 0, 0};
        return new long[]{u32(moov, trex[0] + 20), u32(moov, trex[0] + 24), u32(moov, trex[0] + 28)};
    }

    private static void readMoof(byte[] moof, Piece fragment, long[] trex, long timescale,
                                 boolean video, Samples samples) throws IOException {
        int[] traf = findBox(moof, 8, moof.length, "traf");
        if (traf == null) throw new IOException("no traf");
        int end = traf[0] + traf[1];
        long defaultDuration = trex[0];
        long defaultSize = trex[1];
        long defaultFlags = trex[2];
        long base = fragment.offset;           // default-base-is-moof
        long decodeTime = -1;
        int p = traf[0] + 8;
        while (p + 8 <= end) {
            int size = (int) u32(moof, p);
            String type = type(moof, p + 4);
            if (size < 8) break;
            long versionFlags = u32(moof, p + 8);
            int version = (int) (versionFlags >>> 24);
            int flags = (int) (versionFlags & 0xffffff);
            if ("tfhd".equals(type)) {
                int q = p + 16;                  // after track_ID
                if ((flags & 0x01) != 0) { base = u64(moof, q); q += 8; }
                if ((flags & 0x02) != 0) q += 4;
                if ((flags & 0x08) != 0) { defaultDuration = u32(moof, q); q += 4; }
                if ((flags & 0x10) != 0) { defaultSize = u32(moof, q); q += 4; }
                if ((flags & 0x20) != 0) { defaultFlags = u32(moof, q); q += 4; }
            } else if ("tfdt".equals(type)) {
                decodeTime = version == 1 ? u64(moof, p + 12) : u32(moof, p + 12);
            } else if ("trun".equals(type)) {
                int count = (int) u32(moof, p + 12);
                int q = p + 16;
                long dataOffset = moof.length + 8; // mdat right after moof
                if ((flags & 0x01) != 0) { dataOffset = (int) u32(moof, q); q += 4; }
                long firstFlags = -1;
                if ((flags & 0x04) != 0) { firstFlags = u32(moof, q); q += 4; }
                if (decodeTime < 0) decodeTime = samples.mediaDuration;
                long bytes = 0;
                for (int i = 0; i < count; i++) {
                    long duration = defaultDuration;
                    long sampleSize = defaultSize;
                    long sampleFlags = i == 0 && firstFlags >= 0 ? firstFlags : defaultFlags;
                    long composition = 0;
                    if ((flags & 0x100) != 0) { duration = u32(moof, q); q += 4; }
                    if ((flags & 0x200) != 0) { sampleSize = u32(moof, q); q += 4; }
                    if ((flags & 0x400) != 0) { sampleFlags = u32(moof, q); q += 4; }
                    if ((flags & 0x800) != 0) {
                        composition = version == 0 ? u32(moof, q) : (int) u32(moof, q);
                        q += 4;
                    }
                    if (composition < 0) throw new IOException("negative composition offset");
                    samples.sampleOffsets.add(base + dataOffset + bytes);
                    samples.decodeTimes.add(decodeTime);
                    samples.sampleRuns.add(samples.runs.size());
                    samples.sizes.add((int) sampleSize);
                    samples.durations.add((int) duration);
                    samples.offsets.add((int) composition);
                    if (composition != 0) samples.hasCompositionOffsets = true;
                    if (video && (sampleFlags & 0x10000) == 0) samples.syncSamples.add(samples.sizes.size);
                    bytes += sampleSize;
                    decodeTime += duration;
                    samples.mediaDuration = Math.max(samples.mediaDuration, decodeTime);
                }
                if (q > p + size) throw new IOException("corrupt trun");
                if (bytes > 0) samples.runs.add(new long[]{base + dataOffset, bytes});
            }
            p += size;
        }
    }

    private static byte[] classicMoov(Source video, Source audio, Samples videoSamples,
                                      Samples audioSamples, double duration, boolean large)
            throws IOException {
        byte[] mvhd = boxCopy(video.moov, "mvhd");
        int version = mvhd[8] & 0xff;
        long movieDuration = Math.round(duration * video.movieTimescale);
        writeDuration(mvhd, 8 + 4 + (version == 1 ? 16 : 8) + 4, version, movieDuration);
        putU32(mvhd, mvhd.length - 4, 3);

        byte[] videoTrak = patchTrak(boxCopy(video.moov, "trak"), 1,
                Math.round(videoSamples.mediaDuration * video.movieTimescale / (double) video.mediaTimescale),
                videoSamples.mediaDuration, 1.0);
        byte[] audioTrak = patchTrak(boxCopy(audio.moov, "trak"), 2,
                Math.round(audioSamples.mediaDuration * video.movieTimescale / (double) audio.mediaTimescale),
                audioSamples.mediaDuration,
                audio.movieTimescale == 0 ? 1.0 : video.movieTimescale / (double) audio.movieTimescale);
        videoTrak = replaceStbl(videoTrak, buildStbl(videoTrak, videoSamples, true, large));
        audioTrak = replaceStbl(audioTrak, buildStbl(audioTrak, audioSamples, false, large));

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(mvhd);
        body.write(videoTrak);
        body.write(audioTrak);
        return wrap("moov", body.toByteArray());
    }

    private static byte[] buildStbl(byte[] trak, Samples samples, boolean video, boolean large)
            throws IOException {
        int[] stsd = findPath(trak, "mdia", "minf", "stbl", "stsd");
        if (stsd == null) throw new IOException("no stsd");
        ByteArrayOutputStream stbl = new ByteArrayOutputStream();
        // Same box order as YouTube's progressive MP4s: stsd stts stsc stco stsz stss ctts.
        stbl.write(slice(trak, stsd[0], stsd[1]));
        stbl.write(runLengthBox("stts", samples.durations));
        stbl.write(stsc(samples.chunkSampleCounts));
        // stsz: sample_size 0, count, sizes
        byte[] stsz = new byte[20 + samples.sizes.size * 4];
        header(stsz, "stsz");
        putU32(stsz, 16, samples.sizes.size);
        for (int i = 0; i < samples.sizes.size; i++) putU32(stsz, 20 + i * 4, samples.sizes.values[i] & 0xffffffffL);
        int chunks = samples.chunks.size();
        byte[] stco = new byte[16 + chunks * (large ? 8 : 4)];
        header(stco, large ? "co64" : "stco");
        putU32(stco, 12, chunks);
        for (int i = 0; i < chunks; i++) {
            long offset = samples.chunks.get(i).outputOffset;
            if (large) putU64(stco, 16 + i * 8, offset);
            else putU32(stco, 16 + i * 4, offset);
        }
        stbl.write(stco);
        stbl.write(stsz);
        if (video && samples.syncSamples.size < samples.sizes.size) {
            stbl.write(fullBox("stss", samples.syncSamples, true));
        }
        if (samples.hasCompositionOffsets) stbl.write(runLengthBox("ctts", samples.offsets));
        return wrap("stbl", stbl.toByteArray());
    }

    /** stts/ctts: (count, value) runs. */
    private static byte[] runLengthBox(String type, Ints values) {
        Ints runs = new Ints();
        for (int i = 0; i < values.size; ) {
            int j = i;
            while (j < values.size && values.values[j] == values.values[i]) j++;
            runs.add(j - i);
            runs.add(values.values[i]);
            i = j;
        }
        byte[] box = new byte[16 + runs.size * 4];
        header(box, type);
        putU32(box, 12, runs.size / 2);
        for (int i = 0; i < runs.size; i++) putU32(box, 16 + i * 4, runs.values[i] & 0xffffffffL);
        return box;
    }

    /** Full box holding a count and 32-bit entries. */
    private static byte[] fullBox(String type, Ints entries, boolean counted) {
        byte[] box = new byte[16 + entries.size * 4];
        header(box, type);
        putU32(box, 12, entries.size);
        for (int i = 0; i < entries.size; i++) putU32(box, 16 + i * 4, entries.values[i] & 0xffffffffL);
        return box;
    }

    /** stsc: one entry per change of samples-per-chunk. */
    private static byte[] stsc(Ints chunkSampleCounts) {
        Ints entries = new Ints();
        for (int i = 0; i < chunkSampleCounts.size; i++) {
            if (i == 0 || chunkSampleCounts.values[i] != chunkSampleCounts.values[i - 1]) {
                entries.add(i + 1);
                entries.add(chunkSampleCounts.values[i]);
                entries.add(1);
            }
        }
        byte[] box = new byte[16 + entries.size * 4];
        header(box, "stsc");
        putU32(box, 12, entries.size / 3);
        for (int i = 0; i < entries.size; i++) putU32(box, 16 + i * 4, entries.values[i] & 0xffffffffL);
        return box;
    }

    private static byte[] replaceStbl(byte[] trak, byte[] stbl) {
        return replaceChild(trak, new String[]{"mdia", "minf", "stbl"}, 0, stbl);
    }

    /** Copies a container box, replacing the box at {@code path} and fixing the sizes. */
    private static byte[] replaceChild(byte[] box, String[] path, int depth, byte[] replacement) {
        ByteArrayOutputStream body = new ByteArrayOutputStream(box.length + replacement.length);
        int p = 8;
        while (p + 8 <= box.length) {
            int size = (int) u32(box, p);
            if (size < 8 || p + size > box.length) break;
            String type = type(box, p + 4);
            byte[] child = slice(box, p, size);
            if (type.equals(path[depth])) {
                child = depth == path.length - 1 ? replacement
                        : replaceChild(child, path, depth + 1, replacement);
            }
            body.write(child, 0, child.length);
            p += size;
        }
        return wrap(type(box, 4), body.toByteArray());
    }

    /** Muxed bytes from {@code from} on; fragments are downloaded a few ahead in the background. */
    static InputStream open(final Plan plan, final long from) {
        return new MuxInputStream(plan, from);
    }

    private static final ExecutorService DOWNLOADS = Executors.newFixedThreadPool(3,
            new DownloadThreads());

    private static final class DownloadThreads implements ThreadFactory {
        private int count;

        @Override
        public synchronized Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "mux-download-" + (++count));
            thread.setDaemon(true);
            return thread;
        }
    }

    /**
     * Raw Comparator on purpose: a generic one makes javac emit a bridge method whose
     * MethodParameters attribute crashes the D8 version used by this build.
     */
    @SuppressWarnings("rawtypes")
    private static final class ByStart implements Comparator {
        @Override
        public int compare(Object a, Object b) {
            Piece left = (Piece) a;
            Piece right = (Piece) b;
            int order = Double.compare(left.start, right.start);
            return order != 0 ? order : left.track - right.track;
        }
    }

    /** Downloads one source run (a whole fragment, or its sample data). */
    private static final class RunDownload implements java.util.concurrent.Callable<byte[]> {
        private final Piece piece;

        RunDownload(Piece piece) {
            this.piece = piece;
        }

        @Override
        public byte[] call() throws Exception {
            byte[] data = HttpClient.getRange(piece.url, piece.runOffset,
                    piece.runOffset + piece.runSize - 1, USER_AGENT);
            if (piece.fragment) patchFragment(data, piece.sequence, piece.track);
            return data;
        }
    }

    private static long runKey(Piece piece) {
        return (piece.runOffset << 2) | piece.track;
    }

    private static final class MuxInputStream extends InputStream {
        private final Plan plan;
        /** Runs being downloaded or kept for the pieces still to send. */
        private final java.util.Map<Long, Future<byte[]>> runs = new java.util.HashMap<Long, Future<byte[]>>();
        private int index;          // piece being sent; -1 while sending the header
        private int queued;         // pieces whose runs have been requested
        private byte[] data;
        private int position;
        private int end;
        private boolean closed;

        MuxInputStream(Plan plan, long from) {
            this.plan = plan;
            if (from < plan.init.length) {
                index = -1;
                data = plan.init;
                position = (int) from;
                end = plan.init.length;
                queued = 0;
                fill();
            } else {
                int i = 0;
                while (i < plan.pieces.size() - 1 && plan.pieces.get(i + 1).outputOffset <= from) i++;
                queued = i;
                fill();
                load(i);
                position += (int) (from - plan.pieces.get(i).outputOffset);
            }
        }

        /** Requests the runs of upcoming pieces, at most {@link #PREFETCH_RUNS} at a time. */
        private void fill() {
            while (!closed && queued < plan.pieces.size()) {
                Piece piece = plan.pieces.get(queued);
                Long key = runKey(piece);
                if (!runs.containsKey(key)) {
                    if (runs.size() >= PREFETCH_RUNS) break;
                    runs.put(key, DOWNLOADS.submit(new RunDownload(piece)));
                }
                queued++;
            }
        }

        /** Makes piece {@code i} current; drops runs no later piece needs. */
        private boolean load(int i) {
            index = i;
            data = null;
            if (i >= plan.pieces.size()) return false;
            Piece piece = plan.pieces.get(i);
            Long key = runKey(piece);
            if (!runs.containsKey(key)) runs.put(key, DOWNLOADS.submit(new RunDownload(piece)));
            if (queued <= i) queued = i + 1;
            try {
                long waitStarted = System.currentTimeMillis();
                byte[] run = runs.get(key).get();
                long waited = System.currentTimeMillis() - waitStarted;
                // A wait here means the download is slower than playback (player stalls).
                if (waited > 300) {
                    android.util.Log.w("Mp4Mux", "Waited " + waited + "ms for the next "
                            + run.length / 1024 + " KB of " + (piece.track == 1 ? "video" : "audio"));
                }
                data = run;
                position = (int) (piece.offset - piece.runOffset);
                end = position + piece.size;
            } catch (Exception e) {
                android.util.Log.w("Mp4Mux", "Download failed: " + e);
                return false;
            }
            // Release runs that only earlier pieces used.
            java.util.Set<Long> needed = new java.util.HashSet<Long>();
            for (int j = i; j < queued; j++) needed.add(runKey(plan.pieces.get(j)));
            runs.keySet().retainAll(needed);
            fill();
            return true;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            while (data == null || position >= end) {
                if (closed || !load(index + 1)) return -1;
            }
            int n = Math.min(length, end - position);
            System.arraycopy(data, position, buffer, offset, n);
            position += n;
            return n;
        }

        @Override
        public void close() {
            closed = true;
            for (Future<byte[]> future : runs.values()) future.cancel(true);
            runs.clear();
        }
    }

    // --- MP4 box helpers -------------------------------------------------------------------

    /** Returns {offset, size} of the first box of a type among the boxes in [start, end). */
    private static int[] findBox(byte[] data, int start, int end, String wanted) {
        int p = start;
        while (p + 8 <= end) {
            long size = u32(data, p);
            String type = type(data, p + 4);
            if (size == 1) size = u64(data, p + 8);
            else if (size == 0) size = end - p;
            if (size < 8 || p + size > end) return null;
            if (type.equals(wanted)) return new int[]{p, (int) size};
            p += size;
        }
        return null;
    }

    /** Follows nested container boxes, e.g. ("trak", "mdia", "mdhd") inside a moov. */
    private static int[] findPath(byte[] data, String... path) {
        int start = 8;
        int end = data.length;
        int[] box = null;
        for (String type : path) {
            box = findBox(data, start, end, type);
            if (box == null) return null;
            start = box[0] + 8;
            end = box[0] + box[1];
        }
        return box;
    }

    private static byte[] boxCopy(byte[] container, String type) throws IOException {
        int[] box = findBox(container, 8, container.length, type);
        if (box == null) throw new IOException("no " + type);
        return slice(container, box[0], box[1]);
    }

    private static byte[] wrap(String type, byte[] body) {
        byte[] box = new byte[8 + body.length];
        header(box, type);
        System.arraycopy(body, 0, box, 8, body.length);
        return box;
    }

    private static void header(byte[] box, String type) {
        putU32(box, 0, box.length);
        for (int i = 0; i < 4; i++) box[4 + i] = (byte) type.charAt(i);
    }

    private static void writeDuration(byte[] data, int offset, int version, long value) {
        if (version == 1) putU64(data, offset, value);
        else putU32(data, offset, Math.min(value, 0xffffffffL));
    }

    private static byte[] slice(byte[] data, int offset, int size) {
        byte[] copy = new byte[size];
        System.arraycopy(data, offset, copy, 0, size);
        return copy;
    }

    private static String type(byte[] data, int offset) {
        char[] chars = new char[4];
        for (int i = 0; i < 4; i++) chars[i] = (char) (data[offset + i] & 0xff);
        return new String(chars);
    }

    private static long u32(byte[] data, int offset) {
        return ((data[offset] & 0xffL) << 24) | ((data[offset + 1] & 0xffL) << 16)
                | ((data[offset + 2] & 0xffL) << 8) | (data[offset + 3] & 0xffL);
    }

    private static long u64(byte[] data, int offset) {
        return (u32(data, offset) << 32) | u32(data, offset + 4);
    }

    private static void putU32(byte[] data, int offset, long value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }

    private static void putU64(byte[] data, int offset, long value) {
        putU32(data, offset, value >>> 32);
        putU32(data, offset + 4, value);
    }
}
