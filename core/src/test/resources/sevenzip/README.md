These fixtures contain only dummy bytes: `plugin.dll` is the sequence 0 through 255.
It is not a PE file, mod, or game file.

Created with the official portable 7-Zip 7zr 26.04:

```
7zr a -t7z -m0=BCJ2 -m1=LZMA:d=64k -m2=LZMA:d=64k -m3=LZMA:d=64k -mb0:1 -mb0s1:2 -mb0s2:3 -mtc=off -mta=off bcj2.7z plugin.dll
7zr a -t7z -m0=LZMA2 -mtc=off -mta=off lzma2.7z plugin.dll
```

`7zr l -slt bcj2.7z` reports `Method = BCJ2 LZMA:12 LZMA:12 LZMA:12` for the file.
