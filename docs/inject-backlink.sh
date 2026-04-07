#!/bin/sh
# Inject a "Back to Documentation" bar into all HTML files in a directory.
# Usage: inject-backlink.sh <dir>
DIR="$1"
if [ -z "$DIR" ]; then echo "Usage: $0 <dir>"; exit 1; fi

BAR='<div style="background:#1a1a2e;padding:8px 16px;font-family:sans-serif;font-size:14px;position:sticky;top:0;z-index:10000"><a href="/docs/" style="color:#90caf9;text-decoration:none">\&larr; Back to Stormify Documentation</a></div>'

find "$DIR" -name "*.html" -exec sed -i "s|<body[^>]*>|&$BAR|" {} +
