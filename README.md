# n2t-loader

Load DOIs into n2t.

DOIs and their resolution URLs are extracted from CrossRef's OAI-PMH
UNIXSD XML. These are registered with an n2t instance by binding
the `_t` target property within n2t for identifiers prefixed with `doi:`.

## How to use

Get some XML from OAI-PMH. Then:

```
$ lein repl

(def n2t-password "the_xref_n2t_password")
(def p (create-plumbing))
(run-directory "/path/to/dir/with/oai-pmh/xml" p)
```

Errors are reported in `fails.log`.
