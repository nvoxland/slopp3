(ns slopp.lang
  "The portable vocabulary the single-dialect rule OWES an author — part of
  the slopp syntax rather than a library beside it.

  D3 denies reader conditionals: a stored form must read the same everywhere,
  because a branch nothing compiles is a branch no oracle reaches. That ban
  has a cost, and D3.1 (2026-08-06) settled who pays it. When an author hits a
  platform difference they would have branched on, the answer is a function
  HERE — written once, verified once — not thirty-five lines of the same bit
  math in every project, verified on one of the two platforms it ships to.

  So the test for whether something belongs here is narrow: **would its
  absence make someone want a reader conditional?** A general-purpose
  convenience fails that test and belongs in the project that wants it. A
  thing that is genuinely app-specific fails it too, and gets raised as a
  decision with the example in hand rather than absorbed quietly.

  `:cljc` by construction, and the members touch no platform API at all —
  which is stricter than being portable, and is the point. Code that asks
  what a character IS has already made the claim this namespace exists to
  make unnecessary."
  )

(def ^:private hex-digit
  "Hex character → its value, both cases.

  Keyed by whatever `nth` yields for a string on THIS platform — a Character
  on the JVM, a one-character string in a browser — so a lookup written once
  is correct on both without anyone asking which it is. That question is the
  whole portability trap: `(int c)` answers it on one platform and throws on
  the other, and reasoning about which is what an author does instead of
  measuring."
  (zipmap "0123456789abcdefABCDEF"
          [0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 10 11 12 13 14 15]))

(defn ^:export decode-component
  "Percent- and plus-decode `s` → a string. `nil`/`\"\"` → `\"\"`.

  The portable answer to `URLDecoder/decode` and `decodeURIComponent`, which
  is the platform call an author would otherwise write a reader conditional
  for — and D3.1 says the framework pays that cost once rather than every
  project paying it in bit math it can only verify on one of the two
  platforms it ships to.

  **Portable means narrow, not careful.** This uses `count`, `nth`, `=`, map
  lookup keyed by whatever `nth` yields, `char`, `str` and integer
  arithmetic — and nothing that asks what a character IS. That question is
  precisely the one whose answer differs, so the code never poses it.

  Full UTF-8, including the four-byte range: a code point past the BMP is
  emitted as a SURROGATE PAIR, because a string is UTF-16 on both platforms
  and `char` takes a code unit rather than a code point. A decoder that skips
  that step is correct until it meets an emoji.

  **Malformed input is returned, never thrown.** A `%` with no hex behind it,
  a truncated sequence, a continuation byte where a leader belongs — each is
  passed through as the literal text it was. This reads arbitrary bytes off a
  network, and the answer to garbage is a page rather than a 500."
  [s]
  (let [t (str s)
        n (count t)
        ;; one byte at `i`, when `i` opens a well-formed %XX
        b-at (fn [i] (when (and (< (+ i 2) n) (= (nth t i) \%))
                       (let [h (hex-digit (nth t (+ i 1)))
                             l (hex-digit (nth t (+ i 2)))]
                         (when (and h l) (+ (* 16 h) l)))))
        ;; a CONTINUATION byte is 10xxxxxx; anything else means the sequence
        ;; is truncated or spliced, and we pass the leader through as text
        cont (fn [i] (let [b (b-at i)]
                       (when (and b (>= b 0x80) (< b 0xC0)) b)))
        ;; a code point is UTF-16 code units, and past the BMP that is two
        emit (fn [cp]
               (if (< cp 0x10000)
                 (str (char cp))
                 (let [c (- cp 0x10000)]
                   (str (char (+ 0xD800 (bit-shift-right c 10)))
                        (char (+ 0xDC00 (bit-and c 0x3FF)))))))
        ;; [code-point chars-consumed], or nil when this is not a sequence
        utf8 (fn [i]
               (when-let [b0 (b-at i)]
                 (cond
                   (< b0 0x80) [b0 3]

                   (and (>= b0 0xC0) (< b0 0xE0))
                   (when-let [b1 (cont (+ i 3))]
                     [(+ (bit-shift-left (- b0 0xC0) 6) (- b1 0x80)) 6])

                   (and (>= b0 0xE0) (< b0 0xF0))
                   (let [b1 (cont (+ i 3)) b2 (cont (+ i 6))]
                     (when (and b1 b2)
                       [(+ (bit-shift-left (- b0 0xE0) 12)
                           (bit-shift-left (- b1 0x80) 6)
                           (- b2 0x80)) 9]))

                   (and (>= b0 0xF0) (< b0 0xF8))
                   (let [b1 (cont (+ i 3)) b2 (cont (+ i 6)) b3 (cont (+ i 9))]
                     (when (and b1 b2 b3)
                       [(+ (bit-shift-left (- b0 0xF0) 18)
                           (bit-shift-left (- b1 0x80) 12)
                           (bit-shift-left (- b2 0x80) 6)
                           (- b3 0x80)) 12]))

                   :else nil)))]
    (loop [i 0 out []]
      (if (>= i n)
        (apply str out)
        (let [c (nth t i)]
          (cond
            (= c \+) (recur (inc i) (conj out " "))

            (= c \%) (if-let [[cp len] (utf8 i)]
                       (recur (+ i len) (conj out (emit cp)))
                       (recur (inc i) (conj out (str c))))

            :else (recur (inc i) (conj out (str c)))))))))

(def ^:private percent-of
  "ASCII character → its `%XX` escape, for every character that is not
  unreserved.

  Built with `char` over a `range`, so it is KEYED by whatever this platform
  yields for a character — a Character on the JVM, a one-character string in a
  browser — exactly as [[hex-digit]] is. That is what lets the lookup be
  written once and be correct on both without anyone asking which it is.

  Unreserved is RFC 3986's set (`A-Z a-z 0-9 - . _ ~`), and everything else in
  ASCII is escaped rather than only the characters that break a URL today. An
  encoder that escapes too little is wrong somewhere specific; one that escapes
  too much is merely ugly, and the ugliness is visible."
  (let [unreserved (set (concat (map char (range 65 91))    ; A-Z
                                (map char (range 97 123))   ; a-z
                                (map char (range 48 58))    ; 0-9
                                [\- \. \_ \~]))
        hex        "0123456789ABCDEF"]
    (into {} (for [n (range 128)
                   :let [c (char n)]
                   :when (not (contains? unreserved c))]
               [c (str "%" (nth hex (quot n 16)) (nth hex (rem n 16)))]))))

(defn ^:export encode-component
  "Percent-encode `s` for one URL component → a string. `nil`/`\"\"` → `\"\"`.

  The other half of [[decode-component]], and the pair is the point: this is
  written against the decoder that will read it back, and the round trip is
  what the tests assert. Two functions written apart are two guesses — which is
  how a literal `+` in a name like `merge+` reaches a reader as a space.

  A space becomes `%20`, never `+`. `decode-component` accepts both, but `%20`
  is also correct in a PATH, where `+` is a literal plus — so one spelling
  serves both positions and a caller never has to know which it is building.

  **Non-ASCII passes through VERBATIM, and that is a limit rather than an
  oversight.** Percent-encoding a character requires its CODE POINT, and asking
  what a character IS is the single question this namespace exists so that
  nobody has to ask — `(int c)` answers it on one platform and throws on the
  other, and D3 denies the reader conditional that would branch. So the honest
  encoder is the one that escapes what it can address portably (the whole ASCII
  range, exactly) and leaves the rest alone.

  What that costs is precision on the wire, not correctness in the loop: the
  round trip still holds, because the decoder passes non-`%` text through
  unchanged. A caller who needs strictly RFC 3986 bytes for a non-ASCII name
  needs a platform encoder, and should say so where it is used."
  [s]
  (let [t (str s)
        n (count t)]
    (loop [i 0 out []]
      (if (>= i n)
        (apply str out)
        (let [c (nth t i)]
          (recur (inc i) (conj out (or (percent-of c) (str c)))))))))
