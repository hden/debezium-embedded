---
name: standard-clj
description: Clojure coding style guidance and standard-clj CLI tool usage. Use when editing Clojure files (.clj, .cljs, .cljc, .edn) or when the user asks to format/check Clojure code.
---

# standard-clj

This skill provides expertise in the "Standard Clojure Style" for formatting Clojure code and instructions for using the `standard-clj` CLI tool.

## Coding Style Rules

The style is based on three simple rules:

1. **Lists with a symbol**: Always indent with 2 spaces.
2. **Maps & Vectors**: Align vertically with the first element.
3. **Extra spaces**: Extra spaces for vertical alignment are allowed and encouraged for readability.

### Examples

```clojure
;; Rule 1: Lists with a symbol (2-space indent)
(defn my-fn [x]
  (let [a 1
        b 2]
    (+ x a b)))

;; Rule 2: Maps & Vectors alignment
{:key1 "val1"
 :key2 "val2"}

[1
 2
 3]

;; Rule 3: Extra spaces for vertical alignment
(let [apple  100
      banana 50]
  (+ apple banana))

{:name "Alice"
 :age  30}
```

## `standard-clj` CLI Tool

The `standard-clj` CLI tool is the reference implementation of this style.

### Installation

If the tool is not installed, suggest one of the following methods to the user:

- **Homebrew**: `brew tap oakmac/tap && brew install standard-clj`
- **npm**: `npm install --global @chrisoakman/standard-clojure-style`

### Usage

Always run `standard-clj` commands from the project root if possible.

- **Check formatting**: `standard-clj check <path>` (returns non-zero exit code if issues found)
- **Fix formatting**: `standard-clj fix <path>` (modifies files in place)
- **Standard Input**: `echo '(+ 1 1)' | standard-clj fix -`

## Workflow for Coding Agents

1. **Adherence**: When writing or editing Clojure code, strictly follow the three rules.
2. **Automation**: If you have `standard-clj` installed, prefer running `standard-clj fix` on the files you've modified to ensure consistency.
3. **Validation**: Use `standard-clj check` to verify compliance before finishing a task.

## References

- [Tonsky's Blog: Better Clojure formatting](https://tonsky.me/blog/clojurefmt/)
- [standard-clojure-style-js GitHub](https://github.com/oakmac/standard-clojure-style-js)
