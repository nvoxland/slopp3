(ns slopp.web.client
  "The one way slopp code talks TO an HTTP server — the client half of
  `slopp.web`, whose other half serves.

  It exists because three namespaces were each building their own
  `java.net.http.HttpClient` (`slopp.hub`,
  `slopp.web.jwks`, `slopp.api.cljs`), which made all three untestable for the
  same reason and none of them testable by the same fix. The transport is
  identical in all three; only the POLICY differs, and policy is the part worth
  testing.

  So [[request]] holds no policy at all — an answered request returns whatever
  its status, an unanswered one throws — and each caller keeps its own reading
  of what a 404 means. [[fake-requester]] is the same port in memory.

  **Neither adapter is trustworthy alone.** What makes the fake a claim about
  the world rather than about itself is `requester-contract` in
  `slopp.web.client-test`: one suite, both adapters, the real one over a real
  socket in the `^:external` tier and the fake in-image for free. A fake that
  drifts fails the suite it shares.

  This is the worked example of the pattern in
  The reaches/is distinction: the namespace that IS the reaching
  gets a fake; the namespaces that merely REACH the world through it get their
  isolation by injection, and need no fake of their own."
  (:require [clojure.string :as str]))

(defn ^{:malli/schema
        [:=> {:throws [[:map
                        [:http/error [:= :unreachable]]
                        [:http/url :string]
                        [:http/cause :any]]]}
         [:cat [:map
                [:http/url :string]
                [:http/method {:optional true} :keyword]
                [:http/headers {:optional true} [:map-of :string :string]]
                [:http/body {:optional true} [:maybe :string]]
                [:http/timeout-ms {:optional true} :int]]]
         [:map
          [:http/status :int]
          [:http/body :string]
          [:http/headers [:map-of :string :string]]]]}
  ^{:adapter "http — this IS the reaching. It is the one sanctioned place in
              the store that builds an HttpClient; every other caller takes a
              requester as a parameter and gets `fake-requester` plus
              `requester-contract` for free."}
  ^:export request
  "Perform an HTTP request; the far side's answer as
  `{:http/status :http/headers :http/body}`.

  `req` is `{:http/method :http/url :http/headers :http/body :http/timeout-ms}`.
  `:http/method` defaults to `:get`, `:http/body` is sent as a string,
  `:http/headers` are sent as given. The response body is a STRING and the
  response headers are lowercased, because header case is not something a
  caller should have to guess at.

  The keys are namespaced and deliberately NOT ring's. A ring request says
  `:request-method` and `:uri`; this is a different map with a different
  purpose, and borrowing half a vocabulary is how two shapes get confused for
  one. The route handlers on the far side still speak ring — see the contract
  suite, where both appear a few lines apart and mean different things.

  **This function holds no policy, and that is the whole design.** It does not
  decide a 404 is an error, does not parse the body, does not retry, does not
  log. Every caller in this store wants something different from a non-2xx —
  the heartbeat treats a 400 as a drift alarm and a connection failure as the
  ordinary case, `slopp.web.jwks/fetch-jwks!` throws on both,
  `slopp.webdev.cljs/fetch-contract` throws and then reads EDN. Push any one of
  those in here and the other two have to unpick it.

  So: **an answered request RETURNS, whatever the status; an unanswered one
  THROWS.** That line is not arbitrary. A far side that refused and a far side
  that was never there are different facts, and the moment they share a
  representation the caller cannot tell a bug it owns from a machine that
  simply is not running one. The heartbeat shipped with exactly that conflation
  and it presented as a project that silently never appeared in the picker.

  The `:throws` property is what lets the schema exist at all. A bare `:=>`
  asserts a TOTAL function, and this one throws on an unreachable host — so the
  schema that used to be here was a false claim, `schema-drift` flagged it, and
  it had to be deleted. Declaring the throw makes the return type describe only
  the ANSWERED case, which is what it always meant. That is the CHECKED half:
  an unreachable host is a failure a caller is expected to handle, and it
  arrives as `ex-data` a caller can dispatch on rather than a type they must
  catch broadly. An NPE from three calls down remains unchecked and undeclared,
  as it should be.

  [[fake-requester]] is the in-memory adapter of this same port, and
  `requester-contract` in `slopp.web.client-test` is the suite they both pass."
  [req]
  (let [{:http/keys [method url headers body timeout-ms]} req
        publisher (if body
                    (java.net.http.HttpRequest$BodyPublishers/ofString (str body))
                    (java.net.http.HttpRequest$BodyPublishers/noBody))
        ^java.net.http.HttpRequest$Builder started
        (.method (java.net.http.HttpRequest/newBuilder
                  (java.net.URI/create (str url)))
                 (str/upper-case (name (or method :get)))
                 publisher)
        ^java.net.http.HttpRequest$Builder headed
        (reduce (fn [^java.net.http.HttpRequest$Builder acc [k v]]
                  (.header acc (name k) (str v)))
                started
                headers)
        ^java.net.http.HttpRequest$Builder ready
        (if timeout-ms
          (.timeout headed (java.time.Duration/ofMillis timeout-ms))
          headed)
        ^java.net.http.HttpResponse resp
        ;; The ONLY thing this try covers is the send. Widening it by one
        ;; line would fold url-building and header-reduction into "the far side
        ;; is unreachable" — the exact conflation the ex-info exists to end,
        ;; and the shape the heartbeat shipped.
        (try
          (.send (java.net.http.HttpClient/newHttpClient)
                 (.build ready)
                 (java.net.http.HttpResponse$BodyHandlers/ofString))
          (catch java.io.IOException e
            (throw (ex-info (str "no answer from " url)
                            {:http/error :unreachable
                             :http/url   (str url)
                             :http/cause e}
                            e))))]
    {:http/status  (.statusCode resp)
     :http/body    (.body resp)
     :http/headers (into {}
                         (map (fn [[k vs]] [(str/lower-case (str k))
                                            (str/join ", " vs)]))
                         (.map (.headers resp)))}))

(defn ^:export fake-requester
  "An in-memory adapter of the [[request]] port, serving `routes` at `base-url`.

  `routes` is `{[method path] handler}`, where `handler` takes a ring-ish
  `{:method :path :headers :body}` and answers a ring-ish `{:status :body
  :headers}` — the same shape `slopp.web/serve!` handlers speak, so a test can
  move a far side between the fake and a real server without rewriting it.

  Three behaviours are load-bearing, and all three are pinned by the contract
  suite rather than by this docstring:

  - **A url this fake does not serve THROWS**, exactly as an unreachable host
    does. A fake that answered nil there would let a caller pass its tests
    while conflating `refused` with `absent` — the very bug those tests exist
    to catch, rebuilt inside the test double.
  - **A path it serves at a status it was told to return comes BACK**, 4xx and
    5xx included. Status is data here too.
  - **Header keys reach the handler lowercased**, because that is what a real
    server does and a fake that skipped it would let `(get-in req [:headers
    \"x-probe\"])` pass in one run and fail in the other.

  What this does NOT model is the far side's own parsing: a real
  `slopp.web/serve!` decodes a JSON body into a map before the handler sees it,
  and this hands the handler the string the caller sent. That is the SERVER's
  behaviour, not the transport's — a different server need not do it — so the
  contract suite deliberately does not pin it, and a test that depends on the
  parsed shape must run against a real server to mean anything."
  [base-url routes]
  (let [base (str/replace (str base-url) #"/+$" "")]
    (fn [{:http/keys [method url headers body]}]
      (let [u (str url)]
        (when-not (str/starts-with? u base)
          (throw (ex-info (str "nothing is listening at " u)
                          {:http/error :unreachable
                           :http/url   u
                           :http/cause (java.net.ConnectException.
                                        (str "fake-requester serves " base))
                           :fake/serves base})))
        (let [path    (subs u (count base))
              handler (get routes [(or method :get) path])
              lower   (into {}
                            (map (fn [[k v]] [(str/lower-case (name k)) (str v)]))
                            headers)]
          (if handler
            (let [r (handler {:method  (or method :get)
                              :path    path
                              :headers lower
                              :body    body})]
              {:http/status  (:status r 200)
               :http/body    (str (:body r))
               :http/headers (into {}
                                   (map (fn [[k v]] [(str/lower-case (str k)) (str v)]))
                                   (:headers r))})
            {:http/status 404 :http/body "" :http/headers {}}))))))
