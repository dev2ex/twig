`solid5.rar` was generated with RAR 7.23:

```python
from pathlib import Path
Path('alpha.txt').write_text('first remote entry\n' * 10000)
Path('beta.txt').write_text('second remote entry\n' * 12000)
```

```sh
rar a -ma5 -m3 -md1m -s -ep solid5.rar alpha.txt beta.txt
```

It contains two compressed entries sharing a solid dictionary. Remote tests open
the later entry first and interleave independent streams to verify seek/decode behavior.
