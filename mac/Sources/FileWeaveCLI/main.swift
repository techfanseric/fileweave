import Foundation
import Darwin
import FileWeaveMacKit

@main
struct FileWeaveCommand {
    static func main() async {
        let arguments = Array(CommandLine.arguments.dropFirst())
        // Language affects developer messages only; user text and stable command/
        // error identifiers are never translated. No app preference is changed.
        if let index = arguments.firstIndex(of: "--language"), arguments.indices.contains(index + 1) {
            setenv("FILEWEAVE_CLI_LANGUAGE", arguments[index + 1], 1)
        } else if let value = arguments.first(where: { $0.hasPrefix("--language=") }) {
            setenv("FILEWEAVE_CLI_LANGUAGE", String(value.dropFirst("--language=".count)), 1)
        } else { setenv("FILEWEAVE_CLI_LANGUAGE", "en", 1) }
        let task = Task { await FileWeaveCLI.run(arguments: arguments) }
        var sources: [DispatchSourceSignal] = []
        for number in [SIGINT, SIGTERM] {
            signal(number, SIG_IGN)
            let source = DispatchSource.makeSignalSource(signal: number, queue: .global())
            source.setEventHandler {
                task.cancel()
                // A caller can pipe from a producer that never closes stdin.
                // Bound signal handling even when that synchronous read is stuck.
                DispatchQueue.global().asyncAfter(deadline: .now() + 1) {
                    let message: [String: Any] = ["ok": false, "schemaVersion": 1, "error": ["code": "cancelled", "message": L("操作已取消。")]]
                    if var data = try? JSONSerialization.data(withJSONObject: message) {
                        data.append(10); try? FileHandle.standardOutput.write(contentsOf: data)
                    }
                    exit(130)
                }
            }
            source.resume(); sources.append(source)
        }
        let code = await task.value
        for source in sources { source.cancel() }
        exit(code)
    }
}
