# Thumbnails and frame grabbing

> One section of Twig's hard-won lessons. The index, the ship checklist and the
> shared conventions are in [CLAUDE.md](../../CLAUDE.md); the other sections live
> beside this file in [docs/lessons/](.).

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

