import urllib.request
import urllib.parse
import re

query = 'Tesla news 2026'
req = urllib.request.Request(
    'https://html.duckduckgo.com/html/', 
    data=urllib.parse.urlencode({'q': query}).encode('utf-8'), 
    headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}
)

try:
    html = urllib.request.urlopen(req).read().decode('utf-8')
    snippets = re.findall(r'<a class="result__snippet[^>]*>(.*?)</a>', html, re.IGNORECASE | re.DOTALL)
    print('Found', len(snippets), 'snippets')
    for s in snippets[:3]:
        text = re.sub(r'<[^>]+>', '', s).strip()
        print('-', text)
except Exception as e:
    print('Error:', e)
