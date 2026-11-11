# Twig Buttons and Gestures

[简体中文](buttons-and-gestures.zh-CN.md) · [Back to the README](../README.md)

This guide explains button icons, taps, long presses, double taps, swipes and zoom gestures by screen, with pane, path and selection context to help you use them. It describes **1.18.1 in the current checkout**, checked against the implementation. Older versions may lack some buttons or gestures. Icons use the app's own geometry, with uniform ink and backgrounds for readability.

## Contents

- [Panes and selection](#panes-and-selection)
- [Title bar and pane switching](#title-bar-and-pane-switching)
- [File action strip](#file-action-strip)
- [Path bar, file rows and checkboxes](#path-bar-file-rows-and-checkboxes)
- [Tap rows to select](#tap-rows-to-select)
- [Hidden properties button at the left edge](#hidden-properties-button-at-the-left-edge)
- [Common tasks](#common-tasks)
- [Terminal](#terminal)
- [Music player](#music-player)
- [Video player](#video-player)
- [Image viewer](#image-viewer)
- [Image comparison](#image-comparison)
- [Text, hex and PDF](#text-hex-and-pdf)
- [Websites](#websites)
- [Missing or disabled buttons](#missing-or-disabled-buttons)

## Panes and selection

Twig keeps a left and a right pane. By default, portrait shows one and landscape shows both; settings can force either layout. In single-pane mode, the other pane retains its directory and expanded tree.

| Term | Meaning | How controls use it |
|---|---|---|
| Active pane | The side you are operating on. Touching a side in dual-pane mode activates it. | Most file actions take their source items from this pane. |
| Current directory | The location in the path bar, usually the highlighted directory in the tree. | New items are created here. Transfers normally use the other pane's current directory as their destination. |
| Checked items | Files or folders selected with checkboxes. | Copy, move, compress, rename, delete and properties prefer checked items; with none checked, they normally use the highlighted item. Roots have additional protections. |
| Other pane | The opposite side, even when hidden. | Supplies the default transfer destination. Compare uses both panes' current directories. |

Folders **expand and collapse in place**. Ancestors and other branches can remain visible after you expand a child directory. Check the path bar when choosing a destination; visible filenames alone do not identify the current directory.

## Title bar and pane switching

When the title bar is hidden, Terminal, Music, Panes and Menu move to a fixed area above the scrolling action strip. Their long-press actions work in either position.

| Icon / control | Tap | Long press |
|---|---|---|
| <img src="img/buttons/ic_parent_directory.svg" width="32" alt="Up"> Up | Go up one level. | Scroll to the current directory's row if it is off-screen; collapse all if it is already visible. |
| Twig title | — | Open About Twig. Available on the visible title bar. |
| <img src="img/buttons/ic_terminal.svg" width="32" alt="Terminal"> Terminal | Return to an existing session, or create a local shell if there are no sessions. | Open a shell at the current directory: local shell for local storage, remote shell for SFTP. Other sources do not support this shortcut. |
| <img src="img/buttons/ic_music_note.svg" width="32" alt="Music"> Music | Open the music player without starting paused playback merely by opening the page. | Open the player and start or resume playback, if a queue is available or can be restored. |
| <img src="img/buttons/ic_pane_to_right.svg" width="32" alt="Switch right"> <img src="img/buttons/ic_pane_to_left.svg" width="32" alt="Switch left"> Panes | Activate the other pane; the arrow shows the direction. | Open the current directory in the other pane and activate it. Useful for starting both sides at the same location. |
| <img src="img/buttons/ic_more_vert.svg" width="32" alt="Menu"> Menu | Open the main menu. | Request exit. Ask first when music, terminal sessions, sharing or a transfer is active; otherwise exit directly. |

The main menu includes Settings, View, Thumbnails, Remember location, Fullscreen, Root items, Action strip, Theme, Lock and Exit. Lock appears only when a master password is set.

**Back, Lock and Exit:** Back follows the configured Back-button behaviour; an exit triggered by Back always asks for confirmation. Lock requires unlocking on return and keeps music, terminals and sharing running. Exit stops music, closes terminals, stops sharing and cancels the active transfer. Long-pressing Menu requests Exit.

## File action strip

The strip scrolls and normally has one column in portrait and two in landscape. Use **Menu → Action strip** to reorder or hide entries. Duplicate, Properties and Exit are hidden by default.

“—” means there is no separate long-press shortcut. Availability depends on the source and current state; see [Missing or disabled buttons](#missing-or-disabled-buttons).

| Icon / control | Tap | Long press |
|---|---|---|
| <img src="img/buttons/ic_parent_directory.svg" width="32" alt="Up"> Up | Go up a level; in the usage map, go back a level. | Reveal the current directory's row, or collapse all if it is already visible. No shortcut action in the usage map. |
| <img src="img/buttons/ic_sort.svg" width="32" alt="Sort"> Sort | Open sorting options on the default-settings scope. | Open sorting options directly on Selected directory. |
| <img src="img/buttons/ic_refresh.svg" width="32" alt="Refresh"> Refresh | Reload the directory listing. | Refresh thumbnails for checked items or the current item, including thumbnails within targeted directories. Does not clear the entire app's thumbnail cache. |
| <img src="img/buttons/ic_new_folder.svg" width="32" alt="New"> New | Create a folder in the current directory. | Create a text file in the current directory. |
| <img src="img/buttons/ic_copy.svg" width="32" alt="Copy"> Copy | Open confirmation to review or change the destination. | Start copying directly when the other pane has a valid destination; otherwise open destination selection or confirmation. |
| <img src="img/buttons/ic_duplicate.svg" width="32" alt="Duplicate"> Duplicate (hidden by default) | Create numbered copies in each source item's own directory, with a name preview and editable copy names. | Create copies directly with automatic numbered names. |
| <img src="img/buttons/ic_move.svg" width="32" alt="Move"> Move | Open confirmation to review or change the destination. | Start moving directly when the other pane has a valid destination; otherwise open destination selection or confirmation. |
| <img src="img/buttons/ic_compress.svg" width="32" alt="Compress"> Compress | Choose the archive name, destination, ZIP/7z format, compression level, password and other options. Supports saving beside the source and Move mode. | Pack directly to a valid destination in the other pane, using an automatic name and the remembered format and compression level, without a password or Move mode. An existing archive still requires overwrite confirmation. An invalid destination opens the options instead. |
| <img src="img/buttons/ic_rename.svg" width="32" alt="Rename"> Rename | Edit one name, or open batch rename with a preview for multiple items. | **Immediately rename the original items with numbered names**, such as `notes.txt` → `notes_1.txt`, skipping occupied numbers. Does not create copies. |
| <img src="img/buttons/ic_delete.svg" width="32" alt="Delete"> Delete | Confirm deletion; eligible local items can use the recycle bin. | Skip confirmation only when the recycle bin is enabled and every item can be recycled. Otherwise still confirm. App entries go through Android's uninstall UI. |
| <img src="img/buttons/ic_info.svg" width="32" alt="Properties"> Properties (hidden by default) | Toggle properties, or show aggregate properties for a multiple selection. | Open the second properties tab for one item; its contents depend on the file type. Multiple selections still show aggregate properties. |
| <img src="img/buttons/ic_compare.svg" width="32" alt="Compare"> Compare | Compare the left and right current directories. | Open comparison with left and right inputs reversed. Does not swap the main panes' contents. |
| <img src="img/buttons/ic_treemap.svg" width="32" alt="Usage"> Usage | Analyse the highlighted directory or archive; leave the usage map when already viewing it. | Open Android's storage settings. The system determines the page's appearance. |
| <img src="img/buttons/ic_search.svg" width="32" alt="Search"> Search | Search names recursively in the highlighted or current directory, with patterns such as `*.jpg`; for SQLite databases/tables, open the corresponding search. | Open the configured search-engine homepage in the built-in browser. Works even when file search is unavailable. |
| <img src="img/buttons/ic_history.svg" width="32" alt="Recent"> Recent | Open recent locations. | Open the Continue watching tab directly. |
| <img src="img/buttons/ic_share_wifi.svg" width="32" alt="Sharing off"> <img src="img/buttons/ic_share_wifi_on.svg" width="32" alt="Sharing on"> Share | Open Wi-Fi sharing configuration or the active share's status and address. | Start in the background with saved options, preferring the current directory, and show a toast; stop sharing if it is already running. The solid icon indicates an active share. |
| <img src="img/buttons/ic_exit.svg" width="32" alt="Exit"> Exit (hidden by default) | Request exit, asking first if tasks are active. | **Exit directly and stop tasks, without confirmation.** |

Copy, move and compression show progress and support cancellation. Skipping the initial confirmation does not suppress name conflicts, invalid-destination checks or runtime errors.

Copy, Move and Compare icons mirror with the active pane; the table shows one direction.

## Path bar, file rows and checkboxes

| Location / icon | Gesture | Result |
|---|---|---|
| Path bar | Tap | Switch between the compact path and horizontally scrolling path buttons. |
| Path bar, source icon or expanded path buttons | Long press or double tap | Edit the path to jump directly to a location. Restic locations open a dedicated location dialog. The path bar must be visible and its location editable; pickers sharing this path bar support the gesture too. |
| Expanded path buttons | Tap a segment | Select that directory with its direct children visible and child directories collapsed; keep ancestors open. |
| Path bar's source icon | Tap | Toggle the path buttons. |
| Folder row, icon or expansion arrow | Tap | Normally expand or collapse in place. With Tap rows to select enabled and items already checked, an ordinary folder row toggles selection instead; its icon and arrow still expand or collapse. Confirmed empty folders may have no arrow. |
| File row or icon | Tap | Normally open the file. With Tap rows to select enabled and items already checked, the row toggles selection instead; the icon still opens it. |
| File or folder | Long press | Open its context menu, with actions such as Open with, Share, Favourite, Copy and Properties as supported by the source. |
| Checkbox | Tap | Check or uncheck. In the list, an expanded folder with children cycles through selecting the folder itself → its direct children → clearing the selection. |
| Checkbox | Long press | Repeat the last check or uncheck action across a range. Both endpoints must share a directory, depth and tree branch. |
| Transparent area at the far left of a file row | Tap / long press | Toggle that row's properties / open its second tab. See [Hidden properties button](#hidden-properties-button-at-the-left-edge) below for its location. |
| Favourites or Websites heading | Long press | Enter reordering and drag the handles. Tap outside the handles or press Back to finish; scrolling keeps reordering active. |

### Tap rows to select

Find **Menu → Settings → Tap rows to select**. It is off by default. When selecting a batch, it lets you tap the larger row area instead of aiming at each checkbox.

| State | Tapping the row's name or text area |
|---|---|
| Setting off | Open the file or expand/collapse the folder. Use checkboxes to select. |
| Setting on, no items checked | Still open the file or expand/collapse the folder. Check the first item with its checkbox. |
| Setting on, items already checked | Check or uncheck ordinary files and folders. After unchecking the last item, row taps return to opening or expanding. |

**File icons and thumbnails still open files; folder icons and expansion arrows still expand or collapse.** The hidden properties button, checkbox and long-press menu keep their own actions. These row-selection rules apply to list and grid views; roots do not participate in ordinary file multi-selection.

For example, check `a.txt`, then tap the name area of `b.txt` to select it too. Tap its icon to open it instead. Multi-selection is limited to one directory; selecting an item in another directory clears the previous selection.

### Hidden properties button at the left edge

**The apparently empty space at the left of a file row is also a properties button.** It is transparent with no persistent icon; pressing it shows circular touch feedback.

| View | Where to tap |
|---|---|
| List | Find the target file or folder's row, then tap the **narrow strip against the pane's left edge**. It spans the row's height and stays at the pane edge regardless of tree indentation. For deeply nested entries, it is usually in the blank space to the left of the file icon. |
| Grid | Tap the **top-left corner of the file's thumbnail**. The checkbox is at the top right and has a different purpose. |

- **Tap:** expand the properties card below that row, or collapse it if already open. Multiple files' cards can stay open together.
- **Long press:** open properties directly on the second tab, or switch an existing card to that tab. Tab contents depend on file type; an item with only one tab stays on that tab.
- **Target:** always the row you press, even when other files are checked. Use Properties in the action strip for aggregate information about the checked selection.

This area has no persistent ⓘ icon; the action-strip Properties button uses that icon. Website bookmarks and group headings do not provide this ordinary-file properties shortcut.

Sorting and view mode are independent: a directory can sort by size while staying in list view. Its own override wins, then the nearest ancestor's recursive override, then the global default. Resetting sorting leaves view mode alone, and vice versa.

## Common tasks

### Copy files to another directory

1. Open the destination in one pane, then use Panes to return to the source pane.
2. Check the source items and tap Copy.
3. Review the destination path. Choose another directory if needed; dual-pane mode also lets you browse the other pane before confirming.
4. Confirm and follow progress. Once familiar with the destination, long-press Copy to start directly.

Move uses the same destination flow and removes source files after transfer. Duplicate makes another copy in each source item's own directory, useful before editing.

### Pack files and keep the originals

Check the items, tap Compress, choose ZIP or 7z, review the destination and leave Move mode off. Set a password here if needed; encrypted 7z archives can also hide filenames. The long-press shortcut uses no password and keeps the sources.

### Recover deleted files

Confirm that deletion uses the recycle bin, then restore through the tree's recycle-bin entry. Sources without recycling, including remote sources, delete directly; deletion is not always reversible. Long-press Delete skips confirmation only when the whole selection can be recycled.

### Change sorting for one directory

Select the directory, long-press Sort and enable custom sorting under Selected directory. Leave recursion off to affect only this directory, or enable it for descendants to inherit. Configure list/grid separately through Menu → View.

### Exchange files with a computer

Tap Share in the directory you want to share, review read-only and authentication options, then start. Enter the displayed address in a computer browser or connect with a WebDAV client. Long-press Share to stop or restart; restart reuses saved options and prefers the current directory.

## Terminal

| Icon / control | Tap | Long press |
|---|---|---|
| Back arrow | Return to the previous screen, keeping sessions running. | Open Twig's main screen and keep sessions running, including when the terminal was opened from a launcher shortcut. |
| <img src="img/buttons/ic_close.svg" width="32" alt="Close session"> Close (×) | Close the current session. | **Close all terminal sessions** and leave the terminal. |
| <img src="img/buttons/ic_add.svg" width="32" alt="New session"> New (+) | Create a session on the current server, or a local shell when the current session is local. | — |
| <img src="img/buttons/ic_keyboard.svg" width="32" alt="Keyboard"> Keyboard | Show or hide the software keyboard. | — |
| <img src="img/buttons/ic_more_vert.svg" width="32" alt="Terminal menu"> Menu | Open terminal options. | Try to locate the session's current directory in the file manager. |
| Session dropdown | Switch sessions. | — |

Use long press to select terminal text. Close ends the shell; Back keeps it alive. Locating a directory requires being able to obtain and browse that path. It does not make every storage source a terminal host.

## Music player

| Icon / control | Tap | Long press |
|---|---|---|
| <img src="img/buttons/ic_menu.svg" width="32" alt="Playlist drawer"> List menu (≡) | Open the playlist drawer. | — |
| <img src="img/buttons/ic_more_vert.svg" width="32" alt="Music menu"> More (⋮) | Open player options. | Locate the current track in the file manager, if its source supports locating. |
| <img src="img/buttons/ic_queue.svg" width="32" alt="Current list"> Current list | Open the current playlist. | Send the current track to a playlist. |
| <img src="img/buttons/ic_heart.svg" width="32" alt="Not a favourite"> <img src="img/buttons/ic_heart_filled.svg" width="32" alt="Favourite"> Favourite | Add or remove the track from favourites; solid means favourited. | Show track information. |
| <img src="img/buttons/ic_skip_prev.svg" width="32" alt="Previous track"> <img src="img/buttons/ic_skip_next.svg" width="32" alt="Next track"> Previous / Next | Change tracks. | — |
| <img src="img/buttons/ic_play.svg" width="32" alt="Play"> <img src="img/buttons/ic_pause.svg" width="32" alt="Pause"> Play / Pause | Toggle playback. | — |
| <img src="img/buttons/ic_shuffle.svg" width="32" alt="Shuffle"> Shuffle | Toggle shuffled playback. | **Exit the music player and stop playback.** |
| <img src="img/buttons/ic_repeat.svg" width="32" alt="Repeat"> <img src="img/buttons/ic_repeat_one.svg" width="32" alt="Repeat one"> Repeat | Cycle through off, repeat list and repeat one. | Remove the current track from this list, keeping its file. |
| Title, artist, album or lyrics area | Switch between cover and lyrics in portrait. | — |
| Waveform | Tap or drag to seek. | — |

Tracks inside archives and mounted restic repositories use a temporary queue with normal playback and track controls. They cannot be favourited, saved to persistent playlists or exported. Leaving the player clears the temporary queue.

## Video player

After opening a video from the file list, tap the picture to show or hide the title bar and bottom controls. Controls hide automatically during playback. Narrow screens may put the buttons on two rows without changing their actions.

### Video buttons

| Icon / control | Tap or drag | Long press |
|---|---|---|
| Title-bar Back arrow | Close the video page and save eligible playback progress. | — |
| <img src="img/buttons/ic_play.svg" width="32" alt="Video play"> <img src="img/buttons/ic_pause.svg" width="32" alt="Video pause"> Play / Pause | Toggle playback. After a connection failure, tap Play to retry. | — |
| Seek bar | Drag to preview a time; release to seek there. | — |
| <img src="img/buttons/ic_subtitle.svg" width="32" alt="Video subtitles"> Subtitles | Choose Off, a discovered external subtitle or an embedded track. The dialog also adjusts text subtitle size and colour. | Turn subtitles off, or restore the previously used subtitle in this file. Does not restore if there is no previous choice. |
| <img src="img/buttons/ic_audio_track.svg" width="32" alt="Video audio track"> Audio track | Choose a track or turn audio off. Unsupported tracks are labelled and cannot be selected. | Turn audio off / restore it, preferring the previous track and otherwise trying an available track. |
| <img src="img/buttons/ic_scale.svg" width="32" alt="Video scaling"> Picture scaling | Cycle through Best fit → Crop to fill → Stretch. Best fit preserves the aspect ratio and whole picture; Crop preserves the ratio but may hide edges; Stretch may distort the ratio. | — |
| <img src="img/buttons/ic_rotate_unlock.svg" width="32" alt="Orientation unlocked"> <img src="img/buttons/ic_rotate_lock.svg" width="32" alt="Orientation locked"> Orientation lock | Toggle sensor rotation / lock the current orientation. Rotate to your preferred direction before locking. Applies to this playback only. | — |
| <img src="img/buttons/ic_skip_prev.svg" width="32" alt="Previous episode"> <img src="img/buttons/ic_skip_next.svg" width="32" alt="Next episode"> Previous / Next episode | Change episodes. Shown only with a recognised episode queue and an episode available in that direction. | — |
| <img src="img/buttons/ic_skip_head.svg" width="32" alt="Mark opening boundary"> Skip opening | If unset, record the current position as the end of the opening; if already set, clear that mark. | — |
| <img src="img/buttons/ic_skip_tail.svg" width="32" alt="Mark ending boundary"> Skip ending | If unset, record the current position as the start of the ending, stored as remaining duration; if already set, clear that mark. | — |

When unlocked, video follows the rotation sensor even if Android auto-rotate is off. Closing the player restores normal system behaviour. Orientation lock locks the direction, keeping playback buttons and gestures usable.

### Video picture gestures

| Gesture | Action |
|---|---|
| Tap the picture | Show / hide controls. |
| Double-tap the picture | Play / pause; the left and right parts do the same thing. |
| Hold the playing picture for about half a second | Temporarily speed up playback; release to restore the previous speed. Choose the hold speed under Settings → Video playback. |
| Swipe horizontally | Left seeks backward; right seeks forward. Preview the target time and release to seek. |
| Swipe vertically on the left half | Up increases and down decreases this player window's brightness. |
| Swipe vertically on the right half | Up increases and down decreases media volume. |

Use these gestures on the picture area; buttons and the seek bar keep their own actions. The outermost left and right edges are reserved for Android's Back gesture.

### Episodes, opening/ending skips and playback memory

Episode and opening/ending buttons require a recognised queue of at least two episodes. Media servers supply their episode order when available; ordinary directories group videos by episode numbers in filenames. Two arbitrary videos do not necessarily form a series.

To mark an opening, seek to where the main content starts and tap Skip opening. To mark an ending, seek to the start of the credits and tap Skip ending. The button becomes highlighted and the seek bar shows the marks; tap a marked button again to clear it. **Recording a mark does not immediately seek or change the current playback.** Marks are saved for the series. Subsequent playback skips the recorded opening; reaching the marked ending completes the episode and opens the next one, or closes the player for the final episode. A marked ending advances independently of the auto-next setting.

Settings → Video playback also provides:

- **Auto-play next episode:** controls advancing after a normal episode end.
- **Playback progress memory:** controls resume for ordinary files; media servers may also own progress. Recent → Continue watching reopens unfinished videos.
- **Remember audio, subtitles and picture scaling:** reapplies manual choices for the same file or series. A different episode without a matching track or subtitle may not restore that choice.
- **Open videos with audio off:** off by default. When enabled, each newly opened video or episode starts without audio; use Audio track to restore sound manually. Audio-only playback is unaffected.
- **Subtitle appearance, hold speed** and other options. Text subtitle styling does not change the lettering in image subtitles such as PGS.

## Image viewer

Tap the image to toggle controls; long-press it for the action menu. Controls hide automatically after inactivity.

| Icon / control | Tap | Long press |
|---|---|---|
| Checkbox | Check or uncheck the current image. Selection syncs with the file list when opened from it with selection enabled. | — |
| <img src="img/buttons/ic_chevron_right.svg" width="32" alt="Image navigation arrow"> Previous / Next | Change images; Previous uses the reversed arrow. Horizontal swipes also change images. | — |
| <img src="img/buttons/ic_play.svg" width="32" alt="Start slideshow"> <img src="img/buttons/ic_pause.svg" width="32" alt="Pause slideshow"> Play / Pause | Start or pause the slideshow. | — |
| <img src="img/buttons/ic_shuffle.svg" width="32" alt="Slideshow shuffle"> Shuffle | Toggle random order. | — |
| <img src="img/buttons/ic_fit_rotate.svg" width="32" alt="Auto-fit orientation"> Auto-fit | Toggle automatic image-orientation fitting. | — |
| <img src="img/buttons/ic_delete.svg" width="32" alt="Delete image"> Delete | Confirm deletion of the current image. | Recycle directly when the bin is enabled and the image is eligible; otherwise still confirm. |
| <img src="img/buttons/ic_more_vert.svg" width="32" alt="Image menu"> More | Open image actions. | — |

Scanning slideshows hide Delete. Selection and deletion availability in other modes also depend on how the viewer was opened and the source's permissions.

### Image gestures and zoom

| Gesture | Action |
|---|---|
| Double-tap the image | Cycle through **Fit whole image → Fill with cropping → Actual pixels → Fit whole image**. Fit keeps the whole image visible; Fill can put part outside the screen; Actual uses 1:1 pixels, with dragging to see overflowing areas. |
| Hold the second tap and drag vertically | Zoom continuously with one finger; release to finish. Does not trigger the long-press menu. |

Modes may look identical when their scales coincide. Cropping affects the display only, keeping the original file unchanged.

## Image comparison

Double-tap either image to cycle through **Actual size limited to the pane → Fit whole image to pane → Actual pixels → Initial mode**. The initial mode shrinks large images without enlarging small ones; Fit may enlarge small images; Actual is 1:1. Modes may look identical when their scales coincide.

You can also hold the second tap and drag vertically for continuous one-finger zoom, then release to finish without triggering a long-press menu. With synchronisation enabled, the other side follows viewport changes.

## Text, hex and PDF

| Icon / control | Action |
|---|---|
| <img src="img/buttons/ic_search.svg" width="32" alt="Viewer search"> Search | Search within the current viewer: text in the text viewer, the hex viewer's search options in hex, and PDF text on Android 15 or newer. |
| <img src="img/buttons/ic_edit.svg" width="32" alt="Edit text"> Edit | Enter editing when the text file is writable. Unavailable for read-only sources. |
| <img src="img/buttons/ic_save.svg" width="32" alt="Save text"> Save | Tap to save edited text; long-press to save and exit. Save as is in the menu. |

Text fields and selectable text may also offer Android control-provided double-tap word selection, depending on the control.

### PDF gestures

| Gesture | Action |
|---|---|
| Double-tap a page | Toggle between **cropped margins** and **whole page**. Without detectable margins, cropped mode falls back to fit-width. |
| Hold the second tap and drag vertically | Zoom continuously with one finger; release to finish. |

These zoom gestures work in continuous and single-page modes, independently of the Android 15+ requirement for text search. Margin cropping affects the display only, keeping the PDF file unchanged.

See the [SQLite browser guide](sqlite-browser.md) for database/table expansion, filters and searching.

## Websites

Double-tap the **website content area** in the built-in browser to hide or show the website toolbar. Enable Double-tap to hide/show toolbar in the website's display/toolbar options. It defaults on for new configurations and can also be set through website defaults.

This gesture toggles only the toolbar; status and navigation bars have separate settings. Element inspection takes precedence while picking an element.

With this option enabled, Twig handles the second tap. Disable it to retain the website's native double-tap word selection or zoom behaviour; the resulting behaviour depends on WebView and the page itself.

## Missing or disabled buttons

| Symptom | What to check |
|---|---|
| An action-strip button is missing | Scroll the strip, then check Menu → Action strip. Duplicate, Properties and Exit are hidden by default. |
| Terminal, Music or Panes is missing | When the title bar is hidden, look in the fixed area above the action strip. |
| File actions are dimmed or menu entries absent | Check the selected items, source capabilities, destination write access and whether destination selection is active. Archives and media servers have different capabilities. |
| Long-press Copy still opens a dialog | The other pane needs a valid writable destination; otherwise selection or confirmation is still required. |
| Long-press Delete still asks | Recycling is disabled or at least one selected item cannot be recycled. |
| Long-press Search opens a website | This is the search-engine shortcut. Tap for file-name search. Configure the engine through the Websites heading menu. |
| The path bar is missing | Check single-pane path-bar hiding and bottom path-bar placement. |
| Controls move after rotation | Orientation, pane-layout settings and title-bar visibility determine placement. Find controls by icon and name; their actions stay the same. |

## Maintaining this guide and its icons

SVGs are exported from Android VectorDrawable paths, preserving strokes, opacity, fill rules and group transforms. After changing icons or adding references, run `python3 tools/export-button-icons.py` from the repository. Update the [Chinese guide](buttons-and-gestures.zh-CN.md) alongside this one. Assets live in [img/buttons/](img/buttons/).
