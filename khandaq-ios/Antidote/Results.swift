// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// Swift wrapper for RLMResults
class Results<T: OCTObject> {
    fileprivate let results: RLMResults<AnyObject>

    var count: Int {
        get {
            return Int(results.count)
        }
    }

    var firstObject: T {
        get {
            return results.firstObject() as! T
        }
    }

    var lastObject: T {
        get {
            return results.lastObject() as! T
        }
    }

    init(results: RLMResults<AnyObject>) {
        let name = NSStringFromClass(T.self)
        assert(name == results.objectClassName, "Specified wrong generic class")

        self.results = results
    }

    func indexOfObject(_ object: T) -> Int {
        // RLMResults.index(of:) returns NSNotFound (== UInt.max) when the object isn't in the
        // collection. `Int(UInt.max)` TRAPS in Swift (overflow) — a hard crash, in Release too — which
        // happened on real devices when a just-rendered cell's message wasn't in the live Results (mid
        // update / filtered). Return -1 instead so callers can guard.
        let idx = results.index(of: object)
        if idx == UInt(bitPattern: NSNotFound) {
            return -1
        }
        return Int(idx)
    }

    func sortedResultsUsingProperty(_ property: String, ascending: Bool) -> Results<T> {
        let sortedResults = results.sortedResults(usingKeyPath: property, ascending: ascending)
        return Results<T>(results: sortedResults)
    }

    func sortedResultsUsingDescriptors(_ properties: Array<RLMSortDescriptor>) -> Results<T> {
        let sortedResults = results.sortedResults(using: properties)
        return Results<T>(results: sortedResults)
    }

    func addNotificationBlock(_ block: @escaping (ResultsChange<T>) -> Void) -> RLMNotificationToken {
        return results.addNotificationBlock { rlmResults, changes, error in
            // KHANDAQ (#86): deliver the change SYNCHRONOUSLY when Realm already calls back on the main
            // thread (the case for every UI-confined Realm). Re-dispatching through DispatchQueue.main.async
            // added an extra runloop hop: if a second write landed before the deferred block ran (a 1:1
            // send's optimistic-insert-then-ack double write, or an offline-message flood after reconnect),
            // the live collection a table reads from had already advanced past the stale insertions/deletions
            // carried in `changes`, so UITableView.endUpdates asserted "invalid number of rows" and crashed —
            // reliably on a brand-new friend's near-empty chat where rowCount == messages.count with no slack.
            // Synchronous delivery is the canonical Realm + UITableView pattern (indices match the snapshot).
            let deliver = {
                if let error = error {
                    block(ResultsChange.error(error as NSError))
                    return
                }

                let results: Results<T>? = (rlmResults != nil) ? Results<T>(results: rlmResults!) : nil

                if let changes = changes {
                    block(ResultsChange.update(results,
                                               deletions: changes.deletions as! [Int],
                                               insertions: changes.insertions as! [Int],
                                               modifications: changes.modifications as! [Int]))
                    return
                }

                block(ResultsChange.initial(results))
            }

            if Thread.isMainThread {
                deliver()
            }
            else {
                DispatchQueue.main.async(execute: deliver)
            }
        }
    }

    subscript(index: Int) -> T {
        return results[UInt(index)] as! T
    }

    /// KHANDAQ (#40/#61): bounds-checked access. A live RLMResults (e.g. NGC group peers, refreshed
    /// from several sources) can shrink between a table's row-count and a cellForRow/didSelect call,
    /// and the force-subscript above traps on an out-of-range index. Returns nil instead of crashing.
    func object(at index: Int) -> T? {
        guard index >= 0, index < count else {
            return nil
        }
        return results[UInt(index)] as? T
    }

    func objects(with predicate: NSPredicate) -> Results<T> {
        let matching = results.objects(with: predicate)
        return Results<T>(results: matching)
    }
}

