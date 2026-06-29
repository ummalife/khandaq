#if DEBUG
import UIKit

/// In-app QA panel — no external URL / "Open in Khandaq?" dialog needed.
final class QaDebugController: UITableViewController {
    private struct Action {
        let title: String
        let run: () -> Void
    }

    private let theme: Theme
    private weak var sessionCoordinator: ActiveSessionCoordinator?
    private var actions: [Action] = []
    private var statusLabel: UILabel!

    init(theme: Theme, sessionCoordinator: ActiveSessionCoordinator) {
        self.theme = theme
        self.sessionCoordinator = sessionCoordinator
        super.init(style: .grouped)
        title = "QA Automation"
        buildActions()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "cell")

        statusLabel = UILabel()
        statusLabel.numberOfLines = 0
        statusLabel.font = UIFont.systemFont(ofSize: 12)
        statusLabel.textColor = theme.colorForType(.NormalText)
        statusLabel.text = "Commands run in background. Check Xcode console for qa_ios: logs."
        statusLabel.frame = CGRect(x: 16, y: 0, width: view.bounds.width - 32, height: 60)
        tableView.tableFooterView = statusLabel
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        actions.count
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "cell", for: indexPath)
        cell.textLabel?.text = actions[indexPath.row].title
        cell.textLabel?.textColor = theme.colorForType(.LinkText)
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let action = actions[indexPath.row]
        statusLabel.text = "Running: \(action.title)…"
        action.run()
    }

    private func buildActions() {
        guard let coordinator = sessionCoordinator else {
            return
        }

        actions = [
            Action(title: "Log Tox ID") {
                QaCommandHandler.runAction("log_tox_id", coordinator: coordinator)
            },
            Action(title: "Accept all friend requests") {
                QaCommandHandler.runAction("accept_all_friends", coordinator: coordinator)
            },
            Action(title: "Create public group QATest") {
                QaCommandHandler.runAction("create_public", coordinator: coordinator, params: ["name": "QATest"])
            },
            Action(title: "Join pending group ID (plain hex)") {
                QaCommandHandler.consumePendingCommands(coordinator: coordinator)
            },
            Action(title: "Send friend msg: QA ping") {
                let friends = coordinator.toxManager.objects.friends()
                guard friends.count > 0 else {
                    return
                }
                let friend = friends[0]
                QaCommandHandler.runAction("send_friend", coordinator: coordinator, params: [
                    "tox_id": friend.publicKey,
                    "text": "QA ping from iOS",
                ])
            },
        ]
    }
}
#endif
