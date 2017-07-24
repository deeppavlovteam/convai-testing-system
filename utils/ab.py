import sys
import json
import random
lines = sys.stdin.readlines()
for line in lines:
    d = json.loads(line)
    a_index = random.randint(0, 1)
    b_index = 1 - a_index
    for t in d['thread']:
        t['userId'] = "Alice" if t['userId'] == d['users'][a_index]['id'] else "Bob"
    d['users'][a_index]['id'] = "Alice"
    d['users'][b_index]['id'] = "Bob"
    print(json.dumps(d))
