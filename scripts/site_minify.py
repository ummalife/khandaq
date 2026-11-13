#!/usr/bin/env python3
"""Minify the site's stylesheets and script: comments and layout whitespace, nothing else.

KHANDAQ (SEO, 2026-11-13). pr-cy reported that khandaq.org serves its static resources
unoptimised, following Lighthouse's unminified-css and unminified-javascript audits: style.css and
site.js went out with every comment and every indent. The files in web/ stay the readable sources.
scripts/version-site-assets.py hands deploy-site.sh the output of this module to publish in their
place, and stamps every page with the hash of exactly those bytes.

The output differs from the source in comments and whitespace only. Nothing is renamed and no
value, number, colour or selector is rewritten: that is all the audits measure, and a change this
narrow can be checked mechanically. Stdlib only, like the other website gates, which run offline
and without dependencies (.github/workflows/security-checks.yml).

It fails closed rather than guessing:
  * the output is tokenized again and must give exactly the tokens of the source, so two tokens
    that would run together (a - -b, a / /re/, 1 .toString()) are never glued silently;
  * CSS loses whitespace only after { } ; , : and before { } ; , ), so a descendant combinator
    (.a .b, .a :hover) always keeps its space;
  * JavaScript keeps a line break wherever dropping it could move an automatically inserted
    semicolon, and drops one only next to tokens where that cannot happen;
  * a custom property keeps a space wherever its value had whitespace, because scripts read it
    as text;
  * whatever the tokenizer cannot classify with certainty (a slash right after a closing brace,
    HTML-like comments, escapes outside strings) stops the build with its line number.

    python3 scripts/site_minify.py --self-test    # the fixtures at the end of this file (CI)
"""
from __future__ import annotations

import argparse
import gzip
import re
import sys
import unicodedata
from dataclasses import dataclass
from pathlib import Path


class MinifyError(ValueError):
    """The input holds something this minifier does not handle with certainty."""


@dataclass(frozen=True)
class Minified:
    text: str
    # Whitespace characters the output still carries outside strings, because the language needs
    # them there. Lighthouse counts every one of them as waste, so this is the share it would see.
    kept_whitespace: int


@dataclass
class _Token:
    kind: str
    text: str
    line: int
    gap: bool = False      # whitespace or a comment stood between this token and the previous one
    newline: bool = False  # ...and that gap held a line break (JavaScript only)
    control: bool = False  # a ")" that closes the head of if/while/for/with (JavaScript only)
    custom: str = ""       # "colon", "value" or "end" around the value of a --custom property (CSS)


# Lighthouse: a file is reported when minifying it would save at least 2 KiB of transfer AND at
# least this share of its size (unminified-css.js and unminified-javascript.js).
AUDIT_MIN_BYTES = 2048
AUDIT_MIN_SHARE = {".css": 0.05, ".js": 0.10}
# nginx compresses these types at its default gzip_comp_level, which is 1.
NGINX_GZIP_LEVEL = 1


def minify_file(path: Path) -> Minified:
    text = path.read_text(encoding="utf-8-sig")
    try:
        if path.suffix == ".css":
            return minify_css(text)
        if path.suffix == ".js":
            return minify_js(text)
    except MinifyError as exc:
        raise MinifyError(f"{path.name}: {exc}") from None
    raise MinifyError(f"{path.name}: only .css and .js are minified")


def audit(suffix: str, minified: Minified) -> tuple[float, int, bool]:
    """(share of waste, wasted transfer bytes, would the audit report it) for a served file."""
    if not minified.text:
        return 0.0, 0, False
    share = minified.kept_whitespace / len(minified.text)
    transfer = len(gzip.compress(minified.text.encode("utf-8"), compresslevel=NGINX_GZIP_LEVEL, mtime=0))
    wasted = round(transfer * share)
    return share, wasted, share >= AUDIT_MIN_SHARE[suffix] and wasted >= AUDIT_MIN_BYTES


def _line(text: str, index: int) -> int:
    return text.count("\n", 0, index) + 1


# ---- CSS ------------------------------------------------------------------------------------------

_CSS_NAME = r"(?:[\w-]|[^\x00-\x7f])"
_CSS_TOKEN = re.compile(
    r"(?P<comment>/\*.*?\*/)"
    r"|(?P<ws>[ \t\n\r\f]+)"
    r"|(?P<string>\"(?:[^\"\\\n]|\\.)*\"|'(?:[^'\\\n]|\\.)*')"
    r"|(?P<url>url\([ \t\n\r\f]*[^\"'() \t\n\r\f\\][^\"'() \t\n\r\f\\]*[ \t\n\r\f]*\))"
    rf"|(?P<number>[+-]?(?:[0-9]*\.[0-9]+|[0-9]+)(?:[eE][+-]?[0-9]+)?"
    rf"(?:%|(?:--|-?(?:[A-Za-z_]|[^\x00-\x7f])){_CSS_NAME}*)?)"
    rf"|(?P<at>@-?(?:[A-Za-z_]|[^\x00-\x7f]){_CSS_NAME}*)"
    rf"|(?P<hash>\#{_CSS_NAME}+)"
    rf"|(?P<ident>(?:--|-?(?:[A-Za-z_]|[^\x00-\x7f])){_CSS_NAME}*\(?)"
    r"|(?P<delim>[^\\])",
    re.S | re.I,
)
# Whitespace next to these never separates two things that mean something together. Not "(":
# "and (" would turn into the function token "and(". Not ")" on its left: in a selector,
# ":not(.a) .b" is a descendant and ":not(.a).b" is not. Not ">", "+" or "~": calc() needs the
# spaces around + and -, and the few bytes elsewhere are not worth a context rule.
_CSS_DROP_AFTER = frozenset({"{", "}", ";", ",", ":"})
_CSS_DROP_BEFORE = frozenset({"{", "}", ";", ",", ")"})
_CSS_BOUNDARY = frozenset("{}();:,")


def _css_tokens(text: str) -> list[_Token]:
    tokens: list[_Token] = []
    gap = False
    pos = 0
    while pos < len(text):
        m = _CSS_TOKEN.match(text, pos)
        if m is None:  # every character but a backslash matches something
            raise MinifyError(f"line {_line(text, pos)}: escapes outside strings are not supported")
        pos = m.end()
        kind, value = m.lastgroup, m.group()
        if kind == "delim" and value in "\"'":
            raise MinifyError(f"line {_line(text, m.start())}: unterminated string")
        if kind == "delim" and value == "/" and text.startswith("/*", m.start()):
            raise MinifyError(f"line {_line(text, m.start())}: unterminated comment")
        if kind == "comment":
            # A comment is not whitespace in CSS: "a/**/b" is two tokens with nothing between them,
            # and dropping the comment would glue them into one.
            before = text[m.start() - 1] if m.start() else " "
            after = text[m.end()] if m.end() < len(text) else " "
            if before not in " \t\n\r\f" and after not in " \t\n\r\f" and not (
                    before in _CSS_BOUNDARY or after in _CSS_BOUNDARY):
                raise MinifyError(f"line {_line(text, m.start())}: a comment is all that separates "
                                  f"two tokens; put a space next to it")
            gap = True
            continue
        if kind == "ws":
            gap = True
            continue
        tokens.append(_Token(kind, value, _line(text, m.start()), gap=gap))
        gap = False
    return tokens


def _mark_custom_values(tokens: list[_Token]) -> None:
    """Find the value of every --custom property: it is kept with its own spacing."""
    i = 0
    while i < len(tokens):
        start = i == 0 or tokens[i - 1].text in ("{", ";")
        if (start and tokens[i].kind == "ident" and tokens[i].text.startswith("--")
                and i + 1 < len(tokens) and tokens[i + 1].text == ":"):
            tokens[i + 1].custom = "colon"
            depth = 0
            j = i + 2
            while j < len(tokens):
                t = tokens[j]
                if depth == 0 and t.text in (";", "}"):
                    t.custom = "end"
                    break
                if t.text in ("(", "[", "{") or (t.kind == "ident" and t.text.endswith("(")):
                    depth += 1
                elif t.text in (")", "]", "}"):
                    depth -= 1
                t.custom = "value"
                j += 1
            i = j
        i += 1


def _css_without_last_semicolons(tokens: list[_Token]) -> list[_Token]:
    """Drop the optional ";" before a "}". The "}" stands in its place, gap and role included:
    the whitespace that mattered was the one before the ";", not the one after it."""
    out: list[_Token] = []
    for i, t in enumerate(tokens):
        if t.text == ";" and i + 1 < len(tokens) and tokens[i + 1].text == "}":
            close = tokens[i + 1]
            tokens[i + 1] = _Token(close.kind, close.text, close.line, gap=t.gap, custom=t.custom)
            continue
        out.append(tokens[i])
    return out


def _css_separator(prev: _Token, tok: _Token) -> str:
    if not tok.gap:
        return ""
    if tok.custom in ("value", "end"):
        # The value of a --custom property keeps its spacing, down to the space after the colon:
        # scripts read it as text, and older engines return that space with the value.
        return " "
    if prev.text in _CSS_DROP_AFTER or tok.text in _CSS_DROP_BEFORE:
        return ""
    return " "


def _css_significant(tokens: list[_Token]) -> list[_Token]:
    """The tokens that carry meaning, with the optional ";" before a "}" left out."""
    return [t for i, t in enumerate(tokens)
            if not (t.text == ";" and i + 1 < len(tokens) and tokens[i + 1].text == "}")]


def _first_difference(got: list[_Token], want: list[_Token]) -> int | None:
    """The output line of the first token that differs from the source, or None if none does."""
    for a, b in zip(got, want):
        if (a.kind, a.text) != (b.kind, b.text):
            return a.line
    return None if len(got) == len(want) else (got[-1].line if got else 1)


def minify_css(text: str) -> Minified:
    tokens = _css_tokens(text)
    _mark_custom_values(tokens)
    parts: list[str] = []
    kept = 0
    emitted = _css_without_last_semicolons(list(tokens))
    for i, tok in enumerate(emitted):
        if i:
            sep = _css_separator(emitted[i - 1], tok)
            kept += len(sep)
            parts.append(sep)
        parts.append(tok.text)
    out = "".join(parts)
    first = _first_difference(_css_significant(_css_tokens(out)), _css_significant(tokens))
    if first is not None:
        raise MinifyError(f"the minified stylesheet does not tokenize like its source (output line "
                          f"{first}); this is a bug in site_minify.py")
    return Minified(out, kept)


# ---- JavaScript -----------------------------------------------------------------------------------

_JS_LINE_BREAKS = "\n\r  "
_JS_PUNCTUATORS = sorted({
    "{", "}", "(", ")", "[", "]", ".", "...", ";", ",", "<", ">", "<=", ">=", "==", "!=", "===",
    "!==", "+", "-", "*", "%", "**", "++", "--", "<<", ">>", ">>>", "&", "|", "^", "!", "~", "&&",
    "||", "??", "?", "?.", ":", "=", "+=", "-=", "*=", "%=", "**=", "<<=", ">>=", ">>>=", "&=",
    "|=", "^=", "&&=", "||=", "??=", "=>", "/", "/=",
}, key=len, reverse=True)
_JS_NUMBER = re.compile(
    r"0[xX][0-9a-fA-F](?:_?[0-9a-fA-F])*n?|0[oO][0-7](?:_?[0-7])*n?|0[bB][01](?:_?[01])*n?"
    r"|(?:(?:[0-9](?:_?[0-9])*)?\.[0-9](?:_?[0-9])*|[0-9](?:_?[0-9])*\.?)(?:[eE][+-]?[0-9](?:_?[0-9])*)?n?"
)
# After these words an expression begins, so "/" opens a regular expression. They are reserved,
# so none of them can be a variable that is being divided.
_JS_BEFORE_EXPRESSION = frozenset({
    "return", "typeof", "instanceof", "in", "new", "delete", "void", "throw", "case", "do",
    "else", "extends",
})
# Words that are keywords in one place and names in another: a "/" after them cannot be told
# apart without a parser, so it stops the build.
_JS_CONTEXTUAL = frozenset({"of", "yield", "await", "async", "let", "static", "get", "set"})
_JS_CONTROL_HEADS = frozenset({"if", "while", "for", "with"})
# A line break after these words is part of the grammar ("return" alone ends the statement), so it
# is dropped only before "}" or ";", where a semicolon would end the statement anyway.
_JS_RESTRICTED = frozenset({"return", "throw", "break", "continue", "yield", "async", "await", "let"})
# These tokens never begin a statement, so a line break in front of them cannot have caused a
# semicolon to be inserted. "!", "~", "++", "--", "(", "[" and "/" can, and keep their break.
_JS_NO_BREAK_BEFORE = frozenset({
    "}", ")", "]", ",", ";", ".", "?.", ":", "?", "=", "==", "===", "!=", "!==", "<", ">", "<=",
    ">=", "&&", "||", "??", "|", "&", "^", "%", "*", "**", "<<", ">>", ">>>", "+=", "-=", "*=",
    "/=", "%=", "**=", "<<=", ">>=", ">>>=", "&=", "|=", "^=", "&&=", "||=", "??=", "+", "-",
})
# After every other punctuator the statement cannot be over: an operator wants its operand, an
# opening bracket its contents. After these it can be.
_JS_BREAK_KEPT_AFTER = frozenset({")", "]", "}", "++", "--"})


def _js_space(ch: str) -> bool:
    return ch in "\t\v\f  ﻿" or (not ch.isascii() and unicodedata.category(ch) == "Zs")


def _js_digit(ch: str) -> bool:
    return "0" <= ch <= "9"


def _js_id_start(ch: str) -> bool:
    if ch.isascii():
        return ch.isalpha() or ch in "$_"
    return ch.isidentifier()


def _js_id_part(ch: str) -> bool:
    if ch.isascii():
        return ch.isalnum() or ch in "$_"
    return ("a" + ch).isidentifier() or ch in "‌‍"


def _js_slash_is_regex(prev: _Token | None, at_line: int) -> bool:
    if prev is None:
        return True
    if prev.kind == "punct":
        if prev.text == ")":
            return prev.control
        if prev.text == "]":
            return False
        if prev.text in ("}", "++", "--"):
            raise MinifyError(f"line {at_line}: a '/' right after '{prev.text}' may be a division or "
                              f"a regular expression; add parentheses or a semicolon")
        return True
    if prev.kind == "word":
        if prev.text in _JS_BEFORE_EXPRESSION:
            return True
        if prev.text in _JS_CONTEXTUAL:
            raise MinifyError(f"line {at_line}: a '/' after '{prev.text}' may be a division or a "
                              f"regular expression; add parentheses")
        return False
    return prev.kind in ("template_head", "template_middle")


def _js_scan_template(text: str, i: int, line: int) -> tuple[int, bool]:
    """From just inside a template part to its end: (index after it, True if it opened ${)."""
    n = len(text)
    while i < n:
        c = text[i]
        if c == "\\":
            i += 2
            continue
        if c == "`":
            return i + 1, False
        if c == "$" and text.startswith("${", i):
            return i + 2, True
        i += 1
    raise MinifyError(f"line {line}: unterminated template literal")


def _js_tokens(text: str) -> list[_Token]:
    tokens: list[_Token] = []
    n = len(text)
    i = 0
    gap = newline = False
    line_start = True  # only whitespace and comments since the last line break
    braces: list[str] = []
    parens: list[bool] = []
    if text.startswith("#!"):
        raise MinifyError("line 1: a hashbang is not supported")

    def push(kind: str, start: int, end: int, control: bool = False) -> None:
        nonlocal gap, newline, line_start
        tokens.append(_Token(kind, text[start:end], _line(text, start), gap=gap, newline=newline,
                             control=control))
        gap = newline = line_start = False

    while i < n:
        c = text[i]
        if c in _JS_LINE_BREAKS:
            gap = newline = line_start = True
            i += 1
            continue
        if _js_space(c):
            gap = True
            i += 1
            continue
        if text.startswith("//", i):
            while i < n and text[i] not in _JS_LINE_BREAKS:
                i += 1
            gap = True
            continue
        if text.startswith("/*", i):
            end = text.find("*/", i + 2)
            if end < 0:
                raise MinifyError(f"line {_line(text, i)}: unterminated comment")
            if any(ch in _JS_LINE_BREAKS for ch in text[i:end]):
                newline = line_start = True
            gap = True
            i = end + 2
            continue
        if text.startswith("<!--", i) or (line_start and text.startswith("-->", i)):
            raise MinifyError(f"line {_line(text, i)}: HTML-like comments are not supported")
        start = i
        if c in "'\"":
            i += 1
            while i < n and text[i] != c:
                if text[i] == "\\":
                    i += 2 if not text.startswith("\\\r\n", i) else 3
                    continue
                if text[i] in "\n\r":
                    raise MinifyError(f"line {_line(text, start)}: unterminated string")
                i += 1
            if i >= n:
                raise MinifyError(f"line {_line(text, start)}: unterminated string")
            push("string", start, i + 1)
            i += 1
            continue
        if c == "`":
            i, opened = _js_scan_template(text, i + 1, _line(text, start))
            if opened:
                braces.append("template")
            push("template_head" if opened else "template", start, i)
            continue
        if c == "}" and braces and braces[-1] == "template":
            braces.pop()
            i, opened = _js_scan_template(text, i + 1, _line(text, start))
            if opened:
                braces.append("template")
            push("template_middle" if opened else "template_tail", start, i)
            continue
        if _js_digit(c) or (c == "." and i + 1 < n and _js_digit(text[i + 1])):
            m = _JS_NUMBER.match(text, i)
            i = m.end()
            push("number", start, i)
            continue
        if _js_id_start(c) or (c == "#" and i + 1 < n and _js_id_start(text[i + 1])):
            i += 1
            while i < n and _js_id_part(text[i]):
                i += 1
            if i < n and text[i] == "\\":
                raise MinifyError(f"line {_line(text, i)}: escapes in names are not supported")
            push("word", start, i)
            continue
        if c == "\\":
            raise MinifyError(f"line {_line(text, i)}: escapes in names are not supported")
        if c == "/" and _js_slash_is_regex(tokens[-1] if tokens else None, _line(text, i)):
            i += 1
            in_class = False
            while True:
                if i >= n or text[i] in _JS_LINE_BREAKS:
                    raise MinifyError(f"line {_line(text, start)}: unterminated regular expression")
                ch = text[i]
                if ch == "\\":
                    if i + 1 < n and text[i + 1] in _JS_LINE_BREAKS:
                        raise MinifyError(f"line {_line(text, start)}: unterminated regular expression")
                    i += 2
                    continue
                if ch == "[":
                    in_class = True
                elif ch == "]":
                    in_class = False
                elif ch == "/" and not in_class:
                    break
                i += 1
            i += 1
            while i < n and _js_id_part(text[i]):
                i += 1
            push("regex", start, i)
            continue
        punct = next((p for p in _JS_PUNCTUATORS if text.startswith(p, i)), None)
        if punct is None:
            raise MinifyError(f"line {_line(text, i)}: unexpected character {c!r}")
        if punct == "?." and i + 2 < n and _js_digit(text[i + 2]):
            punct = "?"  # "a?.5:b" is a conditional, not optional chaining
        i += len(punct)
        control = False
        if punct == "{":
            braces.append("brace")
        elif punct == "}":
            if not braces:
                raise MinifyError(f"line {_line(text, start)}: unbalanced '}}'")
            braces.pop()
        elif punct == "(":
            prev = tokens[-1] if tokens else None
            parens.append(bool(prev and prev.kind == "word" and (
                prev.text in _JS_CONTROL_HEADS
                or (prev.text == "await" and len(tokens) > 1 and tokens[-2].text == "for"))))
        elif punct == ")":
            if not parens:
                raise MinifyError(f"line {_line(text, start)}: unbalanced ')'")
            control = parens.pop()
        push("punct", start, i, control=control)
    if braces or parens:
        raise MinifyError("unbalanced brackets at the end of the script")
    return tokens


def _js_break_droppable(prev: _Token, tok: _Token) -> bool:
    if prev.kind in ("template_head", "template_middle"):
        return True
    if prev.kind == "punct" and prev.text not in _JS_BREAK_KEPT_AFTER:
        return True
    if prev.kind == "word" and prev.text in _JS_RESTRICTED:
        return tok.kind == "punct" and tok.text in ("}", ";")
    if tok.kind in ("template_middle", "template_tail"):
        return True  # the "}" that closes a ${...}
    return tok.kind == "punct" and tok.text in _JS_NO_BREAK_BEFORE


def _js_needs_space(prev: _Token, tok: _Token) -> bool:
    a, b = prev.text, tok.text
    if _js_id_part(a[-1]) and (_js_id_part(b[0]) or b[0] in "#\\"):
        return True                     # var x, return x, 1 in x
    if prev.kind == "regex" and _js_id_part(b[0]):
        return True                     # /x/ in y: "in" would become flags
    if prev.kind == "number" and b[0] == ".":
        return True                     # 1 .toString()
    if a[-1] in "+-" and b[0] == a[-1]:
        return True                     # a - -b, a + ++b
    if a[-1] == "/" and b[0] in "/*":
        return True                     # a / /re/ would open a comment
    if a[-1] == "<" and b[0] == "!":
        return True                     # a < !--b would open an HTML-like comment
    if a[-1] == "-" and b[0] == ">":
        return True                     # a-- > b would close one
    if prev.kind == "punct" and tok.kind == "punct":
        joined = a + b
        longest = next((p for p in _JS_PUNCTUATORS if joined.startswith(p)), "")
        return len(longest) > len(a)    # = =, ? ., & &
    return False


def _js_separator(prev: _Token, tok: _Token) -> str:
    if not tok.gap:
        return ""
    if tok.newline and not _js_break_droppable(prev, tok):
        return "\n"
    return " " if _js_needs_space(prev, tok) else ""


def minify_js(text: str) -> Minified:
    tokens = _js_tokens(text)
    parts: list[str] = []
    kept = 0
    for i, tok in enumerate(tokens):
        if i:
            sep = _js_separator(tokens[i - 1], tok)
            kept += len(sep)
            parts.append(sep)
        parts.append(tok.text)
    out = "".join(parts)
    again = _js_tokens(out)
    first = _first_difference(again, tokens)
    if first is not None:
        raise MinifyError(f"the minified script does not tokenize like its source (output line "
                          f"{first}); this is a bug in site_minify.py")
    for i in range(1, len(tokens)):
        src, got = tokens[i], again[i]
        if (got.newline and not src.newline) or (
                src.newline and not got.newline and not _js_break_droppable(tokens[i - 1], src)):
            raise MinifyError(f"line {src.line}: a line break the grammar depends on was changed; "
                              f"this is a bug in site_minify.py")
    return Minified(out, kept)


# ---- self-test ------------------------------------------------------------------------------------

_CSS_CASES = (
    ("a :hover { color : red ; }", "a :hover{color :red}"),
    (".a .b, .c > .d {\n  margin: 0 auto;\n}\n", ".a .b,.c > .d{margin:0 auto}"),
    ("@media (min-width: 760px) and (max-width: 1100px) {\n  .a { b: c; }\n}",
     "@media (min-width:760px) and (max-width:1100px){.a{b:c}}"),
    ("a { width: calc(1px + 2px * -1); }", "a{width:calc(1px + 2px * -1)}"),
    (":root {\n  --ease: cubic-bezier(0.2, 0, 0, 1);\n  --pair:  a ,\n b ;\n}",
     ":root{--ease: cubic-bezier(0.2, 0, 0, 1);--pair: a , b }"),
    (":root{--tight:1px;--last: 2px;}", ":root{--tight:1px;--last: 2px}"),
    ('a { content: "/* kept */  ,  { }"; }', 'a{content:"/* kept */  ,  { }"}'),
    ("a { background: url( x.png ) no-repeat; }", "a{background:url( x.png ) no-repeat}"),
    ("a:not(.b) .c, a:not(.b).d {}", "a:not(.b) .c,a:not(.b).d{}"),
    ("/* head */\n@font-face {\n  src: url(\"f.woff2\") format(\"woff2\");\n}\n/* tail */",
     '@font-face{src:url("f.woff2") format("woff2")}'),
    ("a { b: c !important; } d { e: 1px solid #fff }", "a{b:c !important}d{e:1px solid #fff}"),
)
_CSS_REFUSED = ("a/**/b {}", "a { b: c\\d }", "a { b: 'open }", "a { b: c } /* open")

_JS_CASES = (
    ("var a = b - -c;", "var a=b- -c;"),
    ("x = a + +b + ++c;", "x=a+ +b+ ++c;"),
    ("x = a / /re/.source.length;", "x=a/ /re/.source.length;"),
    ("function f() {\n  return\n  x;\n}", "function f(){return\nx;}"),
    ("function f() {\n  return\n}", "function f(){return}"),
    ("a\n++b", "a\n++b"),
    ("a = 1\nb = 2", "a=1\nb=2"),
    ("foo()\n  .bar()\n  .baz();", "foo().bar().baz();"),
    ("x = 1 .toString();", "x=1 .toString();"),
    ("if (x) /re/.test(y);", "if(x)/re/.test(y);"),
    ("z = (a) / 2 / (b);", "z=(a)/2/(b);"),
    ("s = 'a // b /* c */';", "s='a // b /* c */';"),
    ("r = /[/]\\//g; x = 1;", "r=/[/]\\//g;x=1;"),
    ("t = `a ${ b + `c ${ d }` } e`;", "t=`a ${b+`c ${d}`} e`;"),
    ("x = /re/ instanceof RegExp;", "x=/re/ instanceof RegExp;"),
    ("x = a < !--b;", "x=a< !--b;"),
    ("x = a-- > b;", "x=a-- >b;"),
    ("var/* gone */a = 1;", "var a=1;"),
    ("a = 1/* two\nlines */b = 2", "a=1\nb=2"),
    ("y = a ? .5 : b;", "y=a?.5:b;"),
    ("x = y\n(z)", "x=y\n(z)"),
    ("label: for (;;) {\n  break label;\n}", "label:for(;;){break label;}"),
    ("var o = {\n  a: 1,\n  b: [2, 3]\n};", "var o={a:1,b:[2,3]};"),
    ("x = 'Изменений: ' + n;", "x='Изменений: '+n;"),
)
_JS_REFUSED = (
    "if (a) {}\n/re/.test(b);",   # after "}": block or object literal, cannot tell
    "x = a <!-- b;",
    "var \\u0061 = 1;",
    "x = 'open;",
    "x = /open;",
    "x = (1;",
)


def self_test() -> int:
    failures: list[str] = []
    for minify, cases, refused in ((minify_css, _CSS_CASES, _CSS_REFUSED),
                                   (minify_js, _JS_CASES, _JS_REFUSED)):
        lang = "css" if minify is minify_css else "js"
        for source, want in cases:
            try:
                got = minify(source).text
            except MinifyError as exc:
                failures.append(f"{lang}: {source!r} was refused: {exc}")
                continue
            if got != want:
                failures.append(f"{lang}: {source!r} gave {got!r}, expected {want!r}")
            elif minify(got).text != got:
                failures.append(f"{lang}: {source!r}: minifying the output again changes it")
        for source in refused:
            try:
                got = minify(source).text
            except MinifyError:
                continue
            failures.append(f"{lang}: {source!r} should have been refused, gave {got!r}")
    if failures:
        print("FAIL: site_minify.py self-test:", file=sys.stderr)
        for line in failures:
            print(f"  - {line}", file=sys.stderr)
        return 1
    total = len(_CSS_CASES) + len(_CSS_REFUSED) + len(_JS_CASES) + len(_JS_REFUSED)
    print(f"ok: site_minify.py self-test, {total} fixtures")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--self-test", action="store_true", help="run the fixtures and exit")
    ap.add_argument("files", nargs="*", type=Path, help="print the minified form of these files")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    if not args.files:
        ap.error("give --self-test or files to minify")
    for path in args.files:
        try:
            sys.stdout.write(minify_file(path).text)
        except MinifyError as exc:
            print(f"FAIL: {exc}", file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
