# Media playback

> One section of Twig's hard-won lessons. The index, the ship checklist and the
> shared conventions are in [CLAUDE.md](../../CLAUDE.md); the other sections live
> beside this file in [docs/lessons/](.).

- **AVI playback + thumbnails (★ traced and settled 2026-07-22, `media3` 1.3.1 → 1.9.0)**:
  - **Internal media source URIs must carry a real extension**: non-local backends used to
    get a fake URI with no path (e.g. `"twig://media"`, where `media` parses as an authority,
    not a path). With no recognizable extension, `DefaultExtractorsFactory` degrades to
    `sniff()`ing extractors in a fixed order — and in that order **MP3 comes before AVI**,
    so if an AVI's audio track happens to be matched by `Mp3Extractor` (common with old
    XVID+MP3 AVIs), the whole container parse collapses into a single audio track with a
    duration of a few hundred milliseconds. Changing it to `"twig:///media.$ext"` (note the
    three slashes, so the extension lands in the path) fixes it.
  - **media3 1.3.1's `Mp3Extractor.synchronize` has a known bug with CBR MP3** (fixed in
    1.4.1, upstream commit `b09cea9`; the root cause is `ConstantBitrateSeeker` not knowing
    the end of the file and scanning forward forever for a sync word), showing up as
    `ParserException: Searched too many bytes`. Upgrading to 1.9.0 fixes it (the
    `jellyfin-media3-ffmpeg-decoder` version must be bumped in lockstep, and newer media3
    requires `compileSdk` ≥ 35, which is why `compileSdk` went to 36).
  - **A decoder crashing at runtime ≠ "no decoder"**: on one test device
    `c2.android.mp3.decoder` crashed natively on start (`err 0xe/14`, then released by the
    system). `DefaultRenderersFactory`'s extension fallback (`EXTENSION_RENDERER_MODE_ON`)
    only falls through to ffmpeg when the platform declares no supporting decoder, and can
    do nothing about "declares support but explodes on use". The only fix is to recover in
    `onPlayerError`, in two steps: first switch the whole thing to
    `EXTENSION_RENDERER_MODE_PREFER`, rebuild the player and retry once; if the source's
    audio track itself is partially corrupt (bad samples stuffed with garbage that ffmpeg
    also cannot decode, showing up as playback stalling and triggering
    `ERROR_CODE_TIMEOUT`), drop one more level and just
    `setTrackTypeDisabled(TRACK_TYPE_AUDIO, true)` to play the video silently. Both steps
    need a full player rebuild — in the error state the renderer is already disabled and
    cannot be swapped in place.
  - **AVI thumbnails cannot go through MediaMetadataRetriever**: the system MMR does not
    demux AVI, locally or over the network, so it was always a black image. Once playback
    was fixed we confirmed media3's `AviExtractor` decodes frames fine, so `GlFrameGrabber`
    bypasses MMR: start an invisible ExoPlayer decoding into a `SurfaceTexture` and write a
    minimal EGL/GL pipeline that draws the external OES texture into an ordinary 2D texture
    FBO and `glReadPixels` it. **You cannot just attach an `ImageReader`**: hardware video
    decoders emit device-specific opaque/YUV buffers, and reading them directly without
    sampling through GL gives corrupted output or format errors on many vendors' devices
    (this is also why the official `FrameExtractor` builds a whole `media3-effect` GL
    pipeline; not wanting that dependency's size is why this minimal path is hand-written).
    **Do not take the video size from `onVideoSizeChanged`** to create the Surface — with no
    output Surface, `MediaCodecVideoRenderer` never really starts the decode pipeline and
    that callback never fires, a chicken-and-egg deadlock; read the Format's width/height in
    `onTracksChanged` instead, which does not depend on the pipeline running. The duration is
    only known after the container header (and possibly idx1) is parsed, so unlike MP4/MKV
    the target time cannot be computed up front — instead, in `onEvents`, issue an extra
    `seekTo` as soon as the duration is known. For network sources, seeking to idx1 (usually
    at the end of the file) causes a flurry of small round trips, so reuse the player's own
    `BufferedRandomSource` read-ahead cache; a bare `RandomSource` over the network burns
    through the generation timeout easily.
  - **MP3 audio in an AVI is a byte stream, not frames (★ traced and settled 2026-09-04,
    `AviMp3Reframe.kt`)**: `strh.sampleSize` is 1 and `dwLength` counts **bytes**, so the muxer
    cuts the `01wb` chunks wherever it likes. A LAME/VirtualDub-era file interleaves one
    ~534-byte slice per video frame, and 534 is not a multiple of the 384-byte frame of a
    128kbps/48kHz stream — in `test2.avi` only **9 of the first 4190 frames** start on a chunk
    boundary. media3's `AviExtractor` forwards each chunk as **one sample**, and an MP3 decoder
    handed a buffer that does not begin with a frame header decodes nothing.
    - The symptom is not "no sound": the platform decoder answers `err 0xe/14`
      (`C2SoftMp3Dec: Error in parse mp3 header`), `MediaPlayerActivity`'s fallback rebuilds
      with the ffmpeg renderer, the audio clock then anchors on a garbage timestamp — a file
      opened at 0 reports `state=3 pos=14091` — and never advances, so media3 kills playback
      10 s later with `Player stuck playing with no progress`. Reported as "it opens at 13
      seconds and then stops; I can seek but it stays stuck", and it hits roughly **half** of
      all AVIs — the other half happen to be muxed one frame per chunk.
    - ★ **Do not diagnose this from the video side.** This file is XVID *Advanced* Simple
      Profile with B-frames (`IPBBPBB…`) and the device's only `video/mp4v-es` decoder,
      `c2.android.mpeg4.decoder`, advertises `profile/levels: [1/256 (Simple/6)]` — which looks
      exactly like the culprit and is not: a video-only remux of the same clip plays fine. The
      way to settle it is three clips, not reasoning: video only, `ffmpeg -c copy` remux (its
      AVI muxer writes one 384-byte frame per chunk, so the audio comes out aligned), and a
      byte-exact truncation of the original. Only the third one reproduces.
    - ★ **There is no ffmpeg video fallback to reach for either**, whatever
      `EXTENSION_RENDERER_MODE_PREFER` suggests. `DefaultRenderersFactory` does load
      `ExperimentalFfmpegVideoRenderer` reflectively and logs `Loaded FfmpegVideoRenderer`, but
      in the shipped extension its `supportsFormat` returns `FORMAT_UNSUPPORTED_TYPE` and its
      `createDecoder` returns `null`; `libffmpegJNI.so` exports audio entry points only. The
      log line means the class is present, not that anything can decode.
    - The fix is what every other demuxer does: buffer the audio chunks as one continuous byte
      stream, cut it back into MP3 frames and emit one sample per frame
      (`Mp3FrameTrackOutput`, installed by wrapping `DefaultExtractorsFactory` in
      `AviReframingExtractorsFactory` — only `AviExtractor` is wrapped, every other container is
      untouched). It runs for **every** MP3-in-AVI, aligned or not: an already-aligned file comes
      out byte-identical, so there is no second code path to keep working.
    - ★ **Timestamps come from the frames, not from the chunks.** `ChunkReader` spreads the
      duration evenly over the chunk *count*, which is only true when the chunks are uniform —
      this file opens with an 8000-byte audio preload followed by 534-byte chunks, so from the
      second chunk on the container's timeline is **0.47 s ahead of the audio actually stored
      there**. Anchor once (at the first chunk after a seek) and let the frame durations carry
      the timeline; that is byte-derived, and it is what ffmpeg does for AVI audio with
      `sampleSize == 1`.
  - **★ A false MP3 sync word in leading `0xFF` padding turns into "the whole file plays at 2/3
    speed"**: more than one muxer opens the audio stream with a run of `0xFF`, and where that run
    meets real data it parses as a perfectly valid frame header. Believing the first plausible
    header emitted two junk samples (288 and 128 bytes) ahead of the real audio and skewed the
    timeline by 20ms; the audio sink answered with `Unexpected audio track timestamp discontinuity`
    about twice a second, and each one is a hiccup — **a 15 s clip took 22.5 s to play**. The fix is
    the standard double-sync confirmation: believe a candidate only once a second matching header
    turns up exactly one frame later, then stay locked while frames follow each other.
    ★ The symptom reads as a *video* problem ("it stutters"), and it is not: the clock is the audio
    clock, so anything that stalls the audio drags the picture with it. Split the file with
    `ffmpeg -map 0:a` / `-map 0:v` and time each half against its own duration — the audio-only half
    took 22.5 s for 15 s of content while the video-only half was exact, which pointed straight at
    the reframer. Remuxing the same audio into MKV (`-c copy`) and getting 15 s confirmed the data
    was fine and the AVI path was not.
  - **AVI has no presentation timestamps, and MPEG-4 arrives in three shapes that all need
    repairing (★ traced and settled 2026-09-04, `Mpeg4VopTrackOutput`)**:
    - **Frame order.** MPEG-4 ASP stores frames in **coding** order and displays them in another:
      `I0 P3 B1 B2 P6 B4 B5` (subscript = display slot). Every other container records the display
      time per sample; AVI records nothing, so `AviExtractor` numbers the chunks as it meets them
      and labels `P3` as frame 1. The picture jumps three frames forward and two back — "the image
      keeps shaking".
    - **Packed bitstream.** DivX/XVID-era encoders pack a P-VOP and the B-VOP after it into **one**
      chunk, then emit a 7-byte not-coded VOP as its own chunk to keep the chunk count equal to the
      frame count. A decoder handed the packed chunk decodes the first VOP and reports
      `decoded frame, ignoring further trailing bytes` for the rest, so a third of the frames never
      appear. ★ You will not see this by scanning the first few KB of each chunk — the second VOP
      is thousands of bytes in, so a scan that caps its read length reports one VOP per chunk and
      the file looks ordinary.
    - **Stuffing chunks.** Besides the 7-byte not-coded VOP there are 1-byte `7f` chunks holding no
      VOP at all ("repeat the last frame"). Fed to `c2.android.mpeg4.decoder` they answer
      `failed to decode vop header`, the codec goes to the error state and the file is reported
      undecodable — which is what "this one never played and never made a thumbnail" was.
    - The repair works on VOPs, not chunks: a group is one anchor (I/P/S) plus the B-VOPs after it,
      and it owns the timestamps ("slots") of every chunk it spans, stuffing included — which is
      exactly where a packed chunk's second VOP gets its slot. The display times are that group's
      slots **rotated by one**. Telling a stuffing VOP from a real one means actually parsing
      `vop_coded`, which sits behind a variable-width `vop_time_increment` whose width comes from
      `vop_time_increment_resolution` in the VOL header — so the VOL is parsed once and kept
      (`Mpeg4Headers`).
    - ★ **The first VOP of a chunk must keep whatever precedes it.** A keyframe chunk carries the
      VOS/VO/VOL headers in front of the VOP, and that is the only place the decoder ever looks for
      them: slice the VOP out on its own start code and every file answers
      `PVInitVideoDecoder failed. Unsupported content?` before a frame is decoded.
    - ★ **`c2.android.mpeg4.decoder` advertises `profile/levels: [1/256 (Simple/6)]` and decodes
      B-VOPs perfectly well anyway.** We read that as "this device cannot do Advanced Simple
      Profile", built a whole frame-dropping fallback on it, and shipped a third of the frame rate
      for nothing; every one of these files plays at full rate once the samples reach the decoder
      in one piece. The advertised profile list is not a statement about what a decoder will accept.
    - ★ **media3's ffmpeg extension is no video fallback**, whatever logcat implies.
      `DefaultRenderersFactory` loads `ExperimentalFfmpegVideoRenderer` reflectively and logs
      `Loaded FfmpegVideoRenderer`, but in the shipped extension its `supportsFormat` returns
      `FORMAT_UNSUPPORTED_TYPE` and `createDecoder` returns null, and `libffmpegJNI.so` exports
      audio entry points only. The log line means the class is present, not that anything can decode.
    - ★ **How to settle any of this: time it, do not look at it.** Eyeball verdicts on "does it
      shake" flip-flopped across three rounds here and sent the whole investigation down a blind
      alley. `player: state=3` to `player: ended` against the file's own duration is a number, and
      driving playback with
      `am start -a android.intent.action.VIEW -d file:///... -n com.twig.app/.ui.ViewIntentActivity`
      plus `logcat` needs nobody watching the screen. Better still, run the real `AviExtractor`
      through the repair in a Robolectric test over the real file and print the emitted
      `(timeUs, size)` list — that is what finally showed the junk 288/128-byte audio samples and
      the packed-bitstream frame loss.
    - ★ **"Another app plays it fine" is a real data point — find out how before dismissing it.**
      X-plore plays all of these smoothly, and its logcat shows no video decoder being created,
      which we read as "so there is no picture". Wrong: it does not go through MediaCodec at all,
      it carries a software MPEG-4 decoder of its own (on the order of 80 KB of native code), so
      there is nothing for the platform to log. Twig needs no such thing — every one of these
      files plays at full frame rate through the platform decoder once its samples arrive intact
      — but that is roughly the price if it ever did.
      ★ The way to check "is there a picture at all" is not to reason about logs: record the
      screen (`adb shell screenrecord`) and diff consecutive frames. Eight seconds settled a
      question three rounds of argument had not.
- **Real M2TS (Blu-ray BDAV) playback (★ traced and settled 2026-07-22,
  `M2tsStrippingDataSource` / `PgsTsReader`)**:
  - **Packets are 192 bytes, not 188**: BDAV prefixes every TS packet with a 4-byte
    timestamp, while media3's `TsExtractor` hard-codes a 188-byte stride when looking for
    the sync byte (0x47) and simply reports "unrecognized container". **Even the first
    packet carries the prefix** (the sync byte is at offset 4, not 0) — we got that wrong
    once, so do not assume the stream starts with a sync byte at byte 0. Some tools also
    store plain 188-byte TS streams with an .m2ts extension, so the real packet size must be
    probed (is the sync byte consistently aligned on a 188 or 192 stride?) before deciding to
    strip; never assume from the extension. When stripping, also use
    `ProgressiveMediaSource.Factory`'s two-argument constructor to name `TsExtractor`
    explicitly instead of relying on `DefaultExtractorsFactory`'s sniff order.
  - **HDMV private stream_types collide with the standard registry**: Blu-ray/HDMV reuses a
    few byte values with meanings entirely different from the ATSC/DVB registry, and media3
    only knows the standard ones:
    - `0x86` officially means SCTE-35 splice information; HDMV reuses it for **DTS-HD Master
      Audio** — untreated, the highest-quality DTS-X/DTS-HD MA main track is swallowed as
      SCTE-35 and no audio track is ever created. This app only plays local files and has no
      use for real SCTE-35, so treating it as DTS-HD matches the use case.
    - `0x83` is **TrueHD/Atmos**, and media3 **has no TrueHD/MLP `ElementaryStreamReader`
      anywhere in `extractor.ts`** (not a missing case — it was never implemented), so
      untreated there is no sound at all and no selectable audio track. The Blu-ray spec
      requires every TrueHD stream to embed a backward-compatible AC-3 core (5.1) — we use
      `Ac3Reader` to scan for sync words, skip the interleaved MLP frames and decode only the
      real AC-3 core frames, giving up Atmos immersive channels and TrueHD lossless in
      exchange for audio.
    - `0x90` is **HDMV PGS** (bitmap subtitles), for which media3 likewise has no TS-layer
      sample splitter (it only implements the different DVB subtitle standard). PGS bitmap
      decoding itself exists in media3 (`PgsParser`, used by MKV); what is missing is "how to
      cut PES packets into the samples it expects". The new `PgsTsReader`: **one PES packet
      holds exactly one complete PGS segment**, timestamps come from the PES packet's own PTS
      (unlike a standalone `.sup` file where each segment is preceded by a redundant 'PG'
      magic + PTS + DTS — that is `.sup`'s own invention for living outside a container);
      accumulate PES packets until segment_type = END (0x80) and emit the whole run as one
      sample, matching the granularity of one MKV block per Display Set.
    - All three custom stream_types delegate to `DefaultTsPayloadReaderFactory` (with
      `FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS`) through `BluRayTsPayloadReaderFactory`; nothing is
      rewritten wholesale.
    - Before deciding whether a stream_type is covered by an official descriptor, read
      `readEsInfo` in `TsExtractor`'s source — it only lets a registration_descriptor override
      the raw PMT stream_type when that type is `0x05`/`0x06` (generic placeholders), so
      concrete values like `0x83` are unaffected and there is no conflict with that logic.
  - **TsExtractor's newer API changed**: the old `FLAG_EMIT_RAW_SUBTITLE_DATA` +
    `SubtitleParser.Factory.UNSUPPORTED` combination now throws
    `IllegalStateException: Legacy decoding is disabled` in the newer `TextRenderer` — the
    whole "raw samples + legacy SubtitleDecoder" path is gone. You must omit that flag and
    pass a real `DefaultSubtitleParserFactory`, converting samples to
    `application/x-media3-cues` during extraction.
  - **media3's built-in readers have no defense against malformed input and can take the
    whole player down**: we hit `SpliceInfoDecoder` (malformed SCTE-35 going out of bounds,
    already avoided by disabling the metadata track) and `Ac3Util.parseAc3SyncframeInfo` (one
    file's AC3 frame header had a `frmsizecod` outside the standard range → array index out
    of bounds → `ERROR_CODE_IO_UNSPECIFIED`, thrown on the **extraction thread**, not the
    decode thread, so disabling the audio track does not help). `SafeTsPayloadReader` adds a
    defensive try-catch, applied uniformly at `BluRayTsPayloadReaderFactory`'s exit (covering
    both our own readers and those delegated to the built-in factory): swallow the exception,
    drop that whole packet, call `seek()` to reset internal state and carry on.
  - **Never allocate in a hand-written `DataSource`'s `read()` hot path**:
    `M2tsStrippingDataSource` allocated a `ByteArray(4)` for every 4-byte prefix it skipped,
    and at volume (seek probing, large sequential reads) the allocation rate blew up the GC —
    measured as a full minute of "Waiting for a blocking GC", which was the real root cause of
    "m2ts seeks slowly, thumbnails time out", entirely unrelated to network speed. Reusing a
    member field fixed it. While there, `read()` was changed to loop internally and fill as
    much of the caller's requested `length` as possible (with 192-byte packets, without this
    it can only return 188 bytes per call, forcing hundreds of consecutive calls).
  - **Seeking in extremely high-bitrate huge files (30 GB+, ~30 Mbps) is still slow**: after
    the GC problem was fixed we confirmed the seek lands only once (no repeated binary-search
    re-entry), but it still takes tens of seconds and a lot of reading to render the first
    frame. `DefaultLoadControl` only needs 2 s of buffer
    (`bufferForPlaybackAfterRebufferMs`), so the buffering policy is not to blame. The real
    cost is in media3's own `TsBinarySearchSeeker` finding a renderable keyframe after
    landing — TS has no precise index like MP4/MKV, so it can only interpolate PCR timestamps
    and bisect. Fixing it would mean forking that logic; the payoff for such extreme files was
    judged limited and it is not done.
  - **How to verify**: to check what a stream_type/descriptor really is, do not use `ffprobe`'s
    high-level `codec_tag_string` (it maps the result to a human-readable name and hides the
    raw stream_type value — the string "AC-3" misled us once). Write your own Python that
    parses PAT → PMT → the ES loop and read the raw `stream_type` byte and the descriptors
    (especially the format_identifier of the tag=0x05 registration_descriptor); that is ground
    truth. To de-obfuscate a player crash stack, do not look up line numbers in `mapping.txt`
    by hand (you will hit the illusion of different classes sharing the same obfuscated short
    name — it looked like `AES256Options`/`OpenJSSEPlatform`, entirely unrelated classes,
    simply because a real retrace was never run). Use the SDK's
    `"$ANDROID_HOME"/tools/proguard/bin/retrace.sh` with
    `app/build/outputs/mapping/release/mapping.txt` to resolve properly at method level.
- **Episode auto-play (★ 2026-08-19, `Episodes` + `EpisodeSeries`)**: play the next episode
  automatically when one ends, plus previous/next-episode buttons on the control bar (shown
  only when a queue can be derived). The queue takes **two paths — ask the backend first,
  guess from file names second**:
  - **The backend knows the series structure** (`EpisodeSeries`, currently only
    Jellyfin/Emby): `/Shows/{seriesId}/Episodes?userId=` returns **everything at once**
    (across seasons, already sorted), identical on both servers.
    ★ **For an episode reached from "Continue watching" this is the only workable path** —
    its siblings in the tree are *other shows*, so looking at the same directory can never
    build a queue; it must resolve through the item's own `SeriesId`.
  - **Other backends**: list the video files in the same directory and group them by the
    season/episode numbers in the file name (`Episodes.queueOf`).
  - **★ In file-name parsing, match order is priority, and it is the only thing that
    matters**: episode file names are usually trailed by a string of quality/codec tags full
    of numbers —
    `…S01E01.The.Hedge.Knight.2160p.HBOMax.WEB-DL.DDP.5.1.Atmos.DV.HDR.H.265.mkv`
    contains `2160`, `5`, `1` and `265`, any of which could be mistaken for an episode
    number. So `SxxExx` **returns immediately** on a match and nothing after it is even
    looked at; `E01`/`EP01` comes next; "bare number" comes last and is the strictest (the
    number must be the **last** segment of the name, which is why `Show 1080p BluRay.mkv` is
    rejected).
  - **Fewer than two episodes means no queue** (the buttons stay hidden): real directories are
    full of things like `Holiday photos 01.mp4`, and it is better to offer no button than to
    string a pile of unrelated videos into a "series".
  - **Switching episodes does not recreate the Activity**: the surface is still there, and
    recreating flashes black and re-runs immersive mode / gestures / orientation lock.
    ★ But **once the surface exists there will be no further `surfaceCreated` callback**, so
    the holder must be handed to the resume gate manually (`pendingHolder` + `resumeReady`) so
    the new episode takes the same path as the first playback — otherwise it waits behind the
    gate forever and never starts.
  - **Finishing an episode must report `PlayState.STOP`**: the server ends the "now playing"
    session on it; without it that session hangs around and collides with the next episode's
    START.


- **The player quits mid-playback and the app then asks for the master password again
  (★ traced and settled 2026-08-31, `media3` 1.9.0)**: symptom was reported as two separate
  problems — "4K HDR playback exits after a while" and "and then I have to unlock again" —
  but it is one event. The device's crash buffer had three `com.twig.app` records, all
  `java.lang.OutOfMemoryError … target footprint 268435456, growth limit 268435456` on the
  `ExoPlayer:Playback` thread (plus one native `SIGABRT` in `MediaCodec_loop` whose abort
  message was `could not create MediaCodec.BufferInfo object` — the same starved heap, just
  from the JNI side). **The unlock prompt is not a bug**: `Secrets` keeps the DEK in a
  `@Volatile` field, so a dead process means `locked()` is true again and `SecurityUi.gate`
  does exactly what it should. Diagnosing this from the unlock symptom alone leads nowhere;
  go read `logcat -b crash` first.
  - **Root cause**: `MediaPlayerActivity` built its `ExoPlayer` with no `LoadControl`, taking
    media3's defaults — `DEFAULT_VIDEO_BUFFER_SIZE` is **125MB** and `DEFAULT_MUXED_BUFFER_SIZE`
    **137MB**, against a `dalvik.vm.heapgrowthlimit` of **256MB**. Add `BufferedRandomSource`'s
    own 24MB block cache and a 60fps HDR10 remux fills the heap in well under a minute.
  - **★ media3's small local-playback tier does not save us**: 1.9 added
    `DEFAULT_VIDEO_BUFFER_SIZE_FOR_LOCAL_PLAYBACK` (18.7MB), but it is selected by URI scheme
    against `LOCAL_PLAYBACK_SCHEMES` = `file`/`content`/`data`/`android.resource`/
    `rawresource`/`asset`. Network playback goes through **`twig:///media.$ext`** (the URI has
    to carry a real extension, see the AVI entry above), which matches none of them, so every
    network video lands in the *largest* tier. This is why the same file plays fine from local
    storage and dies over SMB — the difference is the scheme, not the transport.
  - **Fix**: an explicit `DefaultLoadControl` with `targetBufferBytes` = 48MB and 15s/30s
    duration bounds. The byte cap is what governs high-bitrate files; the duration bounds are
    what governs everything else.
  - **`largeHeap` was deliberately rejected**: the device's `dalvik.vm.heapsize` is 512MB so it
    would have worked, but it only doubles the ceiling the buffer then grows into — it treats
    the symptom, and it is against the project's size-first line.
  - **`GlFrameGrabber` builds its own `ExoPlayer` and still takes the defaults.** It grabs one
    frame and releases, so the buffer has no time to fill; left alone on purpose, but it is the
    same trap if it ever starts decoding longer.
