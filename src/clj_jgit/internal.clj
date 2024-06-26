(ns clj-jgit.internal
  (:import
    (clojure.lang Sequential)
    (org.eclipse.jgit.api Git)
    (org.eclipse.jgit.lib ObjectId ObjectIdRef Repository RefDatabase)
    (org.eclipse.jgit.transport RefSpec)
    (org.eclipse.jgit.revwalk RevWalk RevCommit RevTree)
    (org.eclipse.jgit.treewalk TreeWalk CanonicalTreeParser)))

(defn ref-spec ^RefSpec [str]
  (RefSpec. str))

(defn new-rev-walk
  "Creates a new RevWalk instance (mutable), it's a good idea to use `close-rev-walk` once you are done. ;)"
  ^RevWalk [^Git repo]
  (RevWalk. (.getRepository repo)))

(defn close-rev-walk
  "If given `rev-walk` is a JGit RevWalk instance release any of it's used resources, returns nil either way"
  [rev-walk]
  (when (instance? RevWalk rev-walk)
    (.close ^RevWalk rev-walk)))

(defn new-tree-walk
  "Create new recursive TreeWalk instance (mutable)"
  ^TreeWalk [^Git repo ^RevCommit rev-commit]
  (doto
    (TreeWalk. (.getRepository repo))
    (.addTree (.getTree rev-commit))
    (.setRecursive true)))

#_{:clj-kondo/ignore [:unused-binding]}
(defn bound-commit
  "Find a RevCommit object in a RevWalk and bound to it."
  ^RevCommit [^Git repo ^RevWalk rev-walk ^ObjectId rev-commit]
  (.parseCommit rev-walk rev-commit))

(defprotocol Resolvable
  "Protocol for things that resolve ObjectId's."
  (resolve-object ^ObjectId [commit-ish repo]
    "Find ObjectId instance for any Git name: commit-ish, tree-ish or blob. Accepts ObjectId instances and just passes them through."))

#_{:clj-kondo/ignore [:unused-binding]}
(extend-type nil
  Resolvable
  (resolve-object
    ^ObjectId [^String commit-ish ^Git repo]
    nil))

(extend-type String
  Resolvable
  (resolve-object
    ^ObjectId [^String commit-ish ^Git repo]
    (.resolve (.getRepository repo) commit-ish)))

#_{:clj-kondo/ignore [:unused-binding]}
(extend-type ObjectId
  Resolvable
  (resolve-object
    ^ObjectId [commit-ish ^Git repo]
    commit-ish))

#_{:clj-kondo/ignore [:unused-binding]}
(extend-type ObjectIdRef
  Resolvable
  (resolve-object
    ^ObjectId [commit-ish ^Git repo]
    (.getObjectId commit-ish)))

(extend-type Sequential
  Resolvable
  (resolve-object [refs ^Git repo]
    (map #(resolve-object % repo) refs)))

(extend-type Git
  Resolvable
  (resolve-object
    ^ObjectId [^Git repo commit-ish]
    "For compatibility with previous implementation of resolve-object, which would take repo as a first argument."
    (resolve-object commit-ish repo)))

(defn ref-database
  ^RefDatabase [^Git repo]
  (.getRefDatabase ^Repository (.getRepository repo)))

(defn get-refs
  [^Git repo ^String prefix]
  (.getRefs (ref-database repo) prefix))

(defn rev-tree-by-commit
  "Get a new `RevTree` instance for given `rev-commit`, auto-closes given `rev-walk` unless optional `:dispose-rev-walk?` arg is set to `false`"
  ^RevTree [^RevWalk rev-walk ^RevCommit rev-commit & {:keys [dispose-rev-walk?] :or {dispose-rev-walk? true}}]
  (let [tree (->> (doto rev-commit .getTree .getId)
                  (.parseTree rev-walk))]
    (when dispose-rev-walk? (.dispose rev-walk))
    tree))

(defn canonical-tree-parser
  "Returns a new `org.eclipse.jgit.treewalk.CanonicalTreeParser` instance that reflects the repo state at given `commit-ref`"
  [^Git repo commit-ref]
  (when commit-ref
    (let [rev-walk (new-rev-walk repo)
          object-reader (.newObjectReader (.getRepository repo))]
      (->> (resolve-object commit-ref repo)
           (bound-commit repo rev-walk)
           (rev-tree-by-commit rev-walk)
           (CanonicalTreeParser. nil object-reader)))))

(defn get-head-commit "Return HEAD RevCommit instance" [^Git repo]
  (with-open [rev-walk (new-rev-walk repo)]
      (as-> repo $
        (.getRepository $)
        (.resolve $ "HEAD")
        (bound-commit repo rev-walk $))))
