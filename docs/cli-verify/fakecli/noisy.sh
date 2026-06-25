#!/bin/bash
cat >/dev/null
printf '%s\n' '{"response": "ok"}'
python3 -c "import sys; sys.stderr.write('E'*2000000); sys.stderr.flush()"
exit 0
