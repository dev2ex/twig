# Thumbnails and frame grabbing

> One section of Twig's hard-won lessons. The index, the ship checklist and the
> shared conventions are in [AGENTS.md](../../AGENTS.md); the other sections live
> beside this file in [docs/lessons/](.).

- **Trash thumbnails use the recovery payload** (2026-10-28): the `trash` scheme
  previously fell through network eligibility and decoding, so uncached previews
  depended on the network toggle and PDFs never generated. Treat trash as local
  during eligibility, then resolve its validated regular-file payload on the worker
  for ordinary local decoding. Retain the original display name because a top-level
  payload is named `data`. Keep cache keys on the listed file's name/size/mtime so
  previews made before deletion, in trash and after restoration share the cache.
  Never decode the old original path: it may be absent or hold a replacement file.
  Reject symlink payloads just like trash content reads. `TrashThumbnailTest` covers
  uncached images, nested files, replacement originals, cache identity, local
  eligibility and symlink rejection.

- **Package-folder icons must cover the other app storage locations too** (2026-10-21,
  issue #19): icon detection lived inside `AndroidDataAccess` and only recognized
  Android/data, so Android/media, Android/obb and private package directories still
  showed generic folders. `AppPackageFolders` now owns the icon hint independently of
  permission routing. It recognizes direct package directories in external storage
  (including aliases, removable volumes and external-storage SAF document IDs),
  /data/data, per-user credential/device-encrypted storage, adopted storage and mirrors.
  Only local files and the external-storage document provider qualify; nested directories
  and remote paths must not inherit the installed app's icon. Reuse `FileIcons.bindApp`
  and its `pkg:` cache with the folder placeholder for missing apps, regardless of the
  thumbnail preference. Do not stat or canonicalize private paths on the row-binding
  thread to identify an icon. Path coverage and actual recycled-row icon binding are
  covered by `AppPackageFoldersTest` and `AppPackageIconTest`.

- **Network video thumbnails (★ 2026-07-22, the whole `Thumbs.genVideoNetworkFrame`
  saga)**: the core insight is **never let the system `MediaMetadataRetriever` (MMR) seek
  by itself over a slow network** — it ignores the container's Cues index and scans
  Clusters **sequentially from the start** (measured: 111 MB and 14 s on a 74 GB REMUX
  MKV). The right approach is to parse the container yourself and feed MMR only the bytes
  it needs:
  - **MP4/MOV**: `scanTopBoxes` finds moov/mdat, and `findKeyframeOffset` parses the sample
    tables (stts/stss/stsc/stco/stsz) to compute the target keyframe's **exact byte
    offset** (an early version estimated it linearly from the mdat size, which on VBR was
    off by 9 MB+ and reliably produced a black image). The data source
    `NetVideoDataSource` prefetches head + moov + a window around the keyframe **and falls
    back to a positioned read on a miss** (it must not `return -1` as a hard EOF — MMR
    reads outside the prefetched ranges, and a hard EOF fails decoding). 4K/60fps I-frames
    can exceed the 2 MB window, and the fallback read fills in the rest.
  - **MKV (EBML)**: parse SeekHead → locate Cues (usually at the end of the file) → pick a
    CuePoint for the **video track** (Cues index video and subtitle tracks separately;
    picking a subtitle track points at a subtitle block → green frame / RPS errors) → use
    CueRelativePosition to find the keyframe block. Then **synthesize a small self-contained
    MKV** (EBML header + Info + Tracks + one Cluster holding only that video keyframe, with
    all timestamps zeroed) and feed it to MMR — the file contains exactly one frame at
    t = 0, so MMR has nothing else to scan or decode. The keyframe block is located exactly
    by scanning block headers (SimpleBlock: check the keyframe flag; BlockGroup: check that
    there is no ReferenceBlock), **not by trusting CueRelativePosition** (some files omit it
    and the default 0 lands on the Timestamp element → green frame). And **take only that
    one block** (not "from the keyframe to the end of the cluster"), otherwise Android's
    hardware decoder decodes into subsequent frames and produces a green image.
  - **One frame grab gets a 15 s timeout** (MMR has no cancel API and a truncated container
    can hang), running on a dedicated `videoExecutor` rather than the `twig-thumbs` pool.
    The target time is 1/10 of the duration (the beginning is often black or titles).
  - **What cannot be decoded**: 10-bit H.264 (High 10) and some Dolby Vision — **the device
    has no matching decoder in hardware or software** (checked `media_codecs*.xml`).
    Byte positioning cannot fix that; without bundling a full software decoder, the icon is
    all we can show.
  - **How to verify**: when changing container parsing, first check offsets on a desktop
    against real files with `ffprobe`/`ffmpeg`, synthesize the small file and decode a frame
    there, then port to Kotlin. Android-side behavior (especially hardware-decoder green
    frames) can only be seen in logcat (`grep twig`, look for `thumbs: mkv/mp4/video` with
    `frame=`/`ms=`/`miss=`). **Mind the cache**: bad images produced by an older version stay
    cached (key = md5(name:size:mtime), version-independent), so tell the user to clear the
    thumbnail cache in settings before they can see the new result.
- **A fat moov is its own bottleneck (★ 2026-09-04, `planSlimMoov`)**: a 28 GB 4K/60fps
  HEVC release with **8 audio tracks** produced no thumbnail at all over SMB, and its
  properties card was just as slow. Not a decode failure — a moov's sample tables are
  proportional to sample count **per track**, and this one measured 42.76 MB, of which the
  video trak is 9.70 MB; the other 33 MB is audio sample tables (one AV3A track alone is
  11.71 MB) plus a 1.16 MB udta cover, all downloaded and parsed to grab one video frame.
  On top of that, `VIDEO_HEAD_CAP`'s 8 MB head read is, for **any** faststart file, a
  byte-for-byte duplicate of moov's first 8 MB. Roughly 59 MB per thumbnail, well past
  `VIDEO_TIMEOUT_MS` — hence "it only shows up if I wait long enough and retry".
  Two fixes, both about traffic, neither about decoding:
  - **The head read stops at moov's start when moov is at the head.** The head's second
    role (carrying the keyframe near time 0) only exists when moov is at the *tail*.
  - **Only the video trak of a large moov is downloaded** (`planSlimMoov`). The key
    constraint is that we may **not** hand MMR a shorter moov — moov's own size is what
    tells it where mdat begins, so the file layout has to stay byte-identical. Instead
    every unwanted child box is served as an 8-byte `free` header **of the same size**: a
    standard ISO BMFF skip box, so MMR jumps over the body and those bytes are never
    fetched. A miss inside the hole is harmless anyway — `NetVideoDataSource` falls back to
    a positioned read. Our own sample-table parsing gets a separate *compact* moov (header
    + mvhd + video trak); chunk offsets in co64/stco are absolute file positions, so
    dropping boxes around the video trak changes nothing. 42.76 MB → 9.85 MB, and with the
    head fix the whole grab went 58.76 MB → 17.70 MB.
  - **The plan declines rather than guesses**: 64-bit moov header, children that don't tile
    the box exactly, or no video trak → download the whole moov as before. Stubbing boxes
    inside a structure you misread is how you hand MMR a file that is broken rather than
    slim. `SlimMoovTest` guards exactly that, plus "the compact copy and the full moov
    agree on where the keyframe is".
  - **How it was verified** (the desktop-first rule from the entry above, and it is worth
    repeating because it is cheap): build two **sparse** replicas of the 28 GB file — one
    with the full moov, one with the `free` stubs — write only the extents each strategy
    would download, and `ffmpeg -ss` a frame out of both. `du` then reports the real
    network cost (51 MB vs 18 MB) and the two PNGs' md5 must match. They did.

- **Waveform contrast (★ 2026-07-23)**: `Waveform.normalize()` once used `sqrt(rms/max)` to
  "smooth" the dynamic range, with the side effect that quiet passages were stretched almost
  as tall as loud ones, making the waveform look flat and featureless. When changing a
  contrast-style normalization curve, prefer **linear or a power > 1** (which compresses
  small values harder than large ones); avoid square-root-like powers < 1 (they pull small
  and large values together and reduce contrast). When changing such an algorithm, **handle
  cache invalidation too** — the `Waveform` disk cache key does not include an algorithm
  version, so renaming the directory (`waves` → `waves2`) is easier than hoping users clear
  the cache manually; see the video thumbnail cache lesson.
- **Rounded corners on the player's cover art (★ 2026-07-23)**: `clipToOutline` clips the
  View's rectangular bounds; when the cover uses `fitCenter` to preserve aspect ratio, a
  non-matching image leaves transparent letterboxing inside the View — so as long as the
  rounding is done by clipping the View's outline (`clipToOutline`, `background`, …), it
  clips that transparent margin and no rounding is visible. (This is also the root of the
  earlier `SquareImageView`/`centerCrop` back-and-forth: those approaches traded "force the
  image to fill the View bounds" for visible rounding, at the cost of the original aspect
  ratio.) The correct approach (`MusicPlayerActivity.setRoundedCover`) is to compute the
  rectangle the image **actually occupies** after fitCenter scaling (rather than the View's
  bounds) and draw the rounded corners on that rectangle, baked into the bitmap itself —
  original aspect ratio and visible rounding at once, no custom square View needed.

- **Local music waveform latency (★ 2026-10-12)**: reducing RMS sampling was the initial
  suspect, but wall-time profiling on the XQ-DQ72 showed otherwise. A 176.7 s FLAC took
  1285 ms to analyse, of which RMS was only 28 ms; a 229.3 s MP3 took 3871 ms with 57 ms
  of RMS work. Most time was spent feeding and draining MediaCodec, not squaring samples.
  Android's FLAC extractor on this device already outputs `audio/raw`: routing those
  bytes through `c2.android.raw.decoder` adds codec buffer/IPC work without decoding.
  `audio/raw` with PCM16 now feeds `WaveformRms` directly. The same FLAC, with a different temporary filename to
  avoid the waveform cache, took 388 ms after the change, with the same 15,588,632
  samples and 1903 buffers. This is a device/sample measurement, not a universal speedup.
  MP3 still took 3732 ms: reading encoded samples accounted for 90 ms, RMS for 55 ms,
  and the codec input phase for 2790 ms. Lowering RMS sampling alone cannot remove that
  bottleneck. The profile phases are wall time, including IPC/waits, not decoder CPU time.
  Keep the final-result callback: analysis finishes first, then the whole waveform grows
  from its horizontal centre together. No normalization change, so `waves2` remains valid.
  Supported compressed audio now reuses the bundled media3 FFmpeg audio decoder through a small Java
  bridge in its package (the decoder class is package-private in the pinned extension).
  Its normal buffer queues own the native context; do not reflectively invoke JNI or
  concatenate independent MP3 packets. The same 229.3 s MP3 analysed in 573 and 572 ms
  on two uncached copies, with the same 22,017,024 PCM samples as the platform path.
  A generated mono 22.05 kHz VBR MP3 also completed. If software decoding fails or
  produces an incomplete waveform, release it, rewind the extractor and retry the
  platform path with fresh RMS state. Cancellation returns no result and does not retry.
  The choice is based on `FfmpegLibrary.supportsFormat(mime)`, including network sources
  that still share downloaded bytes through AudioCache; unsupported codecs keep the
  platform path. Queue/EOS handling, early EOF, cancellation and decoder release are
  covered by `FfmpegWaveformTest`.
  **AAC/ALAC need their container's codec configuration.** Carry the actual MIME,
  sample rate, channel count and copies of every `csd-N` buffer into the decoder's
  media3 Format. Hardcoding MP3's MIME or dropping AAC's AudioSpecificConfig / ALAC's
  cookie breaks the generic path; reading CSD must not move the original buffers,
  because a platform fallback still needs them. On the same 176.7 s song, AAC/M4A
  went from 3120 ms with the platform decoder to 340 ms with FFmpeg. ALAC/M4A playback
  already worked, but waveform generation failed with `audio/alac NAME_NOT_FOUND`;
  FFmpeg now generates it completely in 488 ms. These timings used new filenames
  to avoid waveform-cache hits. Normalization and the single final animation are unchanged.
