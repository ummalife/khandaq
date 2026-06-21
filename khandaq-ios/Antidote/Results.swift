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
            // KHANDAQ (#90): always hop to a CLEAN main-runloop turn. An earlier attempt (#86) delivered
            // synchronously "when on the main thread", but Thread.isMainThread is also TRUE while the main
            // thread sits inside a dispatch_sync onto the OCTRealmManager queue (every message write does
            // this). Delivering synchronously there ran a UITableView begin/endUpdates nested inside that
            // write context and crashed with "invalid batch updates" (faulting queue: OCTRealmManager queue).
            // The stale-index problem the sync delivery tried to solve is instead handled robustly on the
            // CONSUMER side (ChatPrivateController reconciles indices vs the live count, else reloadData).
            DispatchQueue.main.async {
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

