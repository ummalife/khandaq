// FIXTURE — must trip khandaq-swift-unsafe-shell. Not in any target, never compiled.
import Foundation

func khandaqFixtureShell(_ tainted: String) {
    let p = Process()
    p.launchPath = "/bin/sh"
    p.arguments = ["-c", "echo \(tainted)"]
    try? p.run()
}
