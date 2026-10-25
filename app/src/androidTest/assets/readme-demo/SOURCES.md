# README demo content: sources

Everything the README screenshots show comes from this directory. `ReadmeScreenshots` loads it onto the
emulator for the capture run and removes all of it afterwards. Nothing here names a real artist, album,
product or brand.

## Photos

Each file is a 1280 px wide rendition that Wikimedia Commons serves from the original. Every source page
carries the uploader's own `{{self|cc-zero}}` template, which was checked in the page source on
2026-10-25. Files with any other or an ambiguous tag were skipped.

| File | Source page | Author | Licence |
|---|---|---|---|
| `photos/photo-01.jpg` | [Cap Hmem Crique.jpg](https://commons.wikimedia.org/wiki/File:Cap_Hmem_Crique.jpg) | Smailtn | CC0 1.0 |
| `photos/photo-02.jpg` | [Landscape Eretria Euboea Greece.jpg](https://commons.wikimedia.org/wiki/File:Landscape_Eretria_Euboea_Greece.jpg) | Jebulon | CC0 1.0 |
| `photos/photo-03.jpg` | [Landscape of Mongolia 01.jpg](https://commons.wikimedia.org/wiki/File:Landscape_of_Mongolia_01.jpg) | Bernard Gagnon | CC0 1.0 |
| `photos/photo-04.jpg` | [Agave Flower.jpg](https://commons.wikimedia.org/wiki/File:Agave_Flower.jpg) | Wilfredor | CC0 1.0 |
| `photos/photo-05.jpg` | [Coreopsis P8022391.jpg](https://commons.wikimedia.org/wiki/File:Coreopsis_P8022391.jpg) | Kaldari | CC0 1.0 |
| `photos/photo-06.jpg` | [Nymphaea alba, Alhambra, Granada, Spain.jpg](https://commons.wikimedia.org/wiki/File:Nymphaea_alba,_Alhambra,_Granada,_Spain.jpg) | Jebulon | CC0 1.0 |
| `photos/photo-07.jpg` | [Lac du Lou from below Col de la Chambre, Les Menuires.jpg](https://commons.wikimedia.org/wiki/File:Lac_du_Lou_from_below_Col_de_la_Chambre,_Les_Menuires.jpg) | DimiTalen | CC0 1.0 |
| `photos/photo-08.jpg` | [Beach, Marker Wadden, 2022.jpg](https://commons.wikimedia.org/wiki/File:Beach,_Marker_Wadden,_2022.jpg) | DimiTalen | CC0 1.0 |
| `photos/photo-09.jpg` | [Rocks, Touddmine beach, Tafedna, Morocco, 2008.jpg](https://commons.wikimedia.org/wiki/File:Rocks,_Touddmine_beach,_Tafedna,_Morocco,_2008.jpg) | DimiTalen | CC0 1.0 |
| `photos/photo-10.jpg` | [Gazania krebsiana, Quebec city, Quebec, Canada 131.jpg](https://commons.wikimedia.org/wiki/File:Gazania_krebsiana,_Quebec_city,_Quebec,_Canada_131.jpg) | Wilfredor | CC0 1.0 |
| `photos/photo-11.jpg` | [Okra flower 2.jpg](https://commons.wikimedia.org/wiki/File:Okra_flower_2.jpg) | Wilfredor | CC0 1.0 |
| `photos/photo-12.jpg` | [Beach in Kurskoe, Kirghizistan 01.jpg](https://commons.wikimedia.org/wiki/File:Beach_in_Kurskoe,_Kirghizistan_01.jpg) | Bgag | CC0 1.0 |

## Music

Three tracks synthesised with ffmpeg for this change: three sine-wave chords that change every four seconds,
a slow swell, and a soft decaying low pulse, faded in and out, low-passed at 5 kHz. No sampled material and
no voice. MP3, 160 kbit/s, 48 s each. Artist "Kova Demo"; each track is its own single, so the album name is the title.

| File | Title | Chords (Hz, four per cycle) | Pulse | Artwork |
|---|---|---|---|---|
| `music/harbour-light.mp3` | Harbour Light | C, A minor, F, G | 65.41 Hz every 0.5 s | square crop of `photo-01.jpg` |
| `music/paper-lanterns.mp3` | Paper Lanterns | D, B minor, G, A | 73.42 Hz every 0.75 s | square crop of `photo-05.jpg` |
| `music/slow-tide.mp3` | Slow Tide | G, E minor, C, D | 49.00 Hz every 1 s | square crop of `photo-07.jpg` |

## Voice memos

Three memos synthesised with ffmpeg: brown noise band-passed to 120–900 Hz, mixed with a quiet sine tone
under a slow tremolo. No speech. AAC in `.m4a`, mono, 96 kbit/s, as the recorder cover writes them.

| File | Name shown | Length | Tone |
|---|---|---|---|
| `memos/memo-1.m4a` | Bike shop opening hours | 34 s | 180 Hz |
| `memos/memo-2.m4a` | Grocery list | 21 s | 140 Hz |
| `memos/memo-3.m4a` | Ideas for the garden | 47 s | 210 Hz |

## Notes

`notes/trip-packing.md` and `notes/garden-plan.md` were written for this change.
