// busy-agent — tells Hearth when this Mac is on a call.
//
// Polls Core Media I/O for cameras that are "running somewhere", i.e. in use by any
// app, and Core Audio for microphones some process is recording from, and POSTs
// {source, busy, devices} to <hearth>/api/busy on every change plus a heartbeat every
// 30 s. Hearth ignores a report that stops checking in, so a sleeping Mac can't leave
// the banner stuck on.
//
// Between polls it runs the run loop rather than sleeping. Core Media I/O delivers its
// changes there: a process that only sleeps keeps the camera list it started with, and
// a camera that was running when its driver went away stays running in it for ever.
//
// The camera counts at once. The mic only after MIC_GRACE seconds of continuous use,
// so a few seconds of dictation don't flash the banner.
//
//   busy-agent https://hearth.example.com [--ignore "OBS Virtual Camera"] [--once]

import CoreAudio
import CoreMediaIO
import Foundation

let MIC_GRACE: TimeInterval = 8
let POLL: TimeInterval = 2
let HEARTBEAT: TimeInterval = 30

// MARK: devices

func cmioProperty<T>(_ id: CMIOObjectID, _ selector: Int, _ value: inout T) -> Bool {
    var addr = CMIOObjectPropertyAddress(mSelector: CMIOObjectPropertySelector(selector),
        mScope: CMIOObjectPropertyScope(kCMIOObjectPropertyScopeWildcard),
        mElement: CMIOObjectPropertyElement(kCMIOObjectPropertyElementWildcard))
    var used: UInt32 = 0
    return CMIOObjectGetPropertyData(id, &addr, 0, nil, UInt32(MemoryLayout<T>.size), &used, &value) == 0
}

func cameras() -> [(name: String, running: Bool)] {
    var addr = CMIOObjectPropertyAddress(mSelector: CMIOObjectPropertySelector(kCMIOHardwarePropertyDevices),
        mScope: CMIOObjectPropertyScope(kCMIOObjectPropertyScopeGlobal),
        mElement: CMIOObjectPropertyElement(kCMIOObjectPropertyElementMain))
    var size: UInt32 = 0
    guard CMIOObjectGetPropertyDataSize(CMIOObjectID(kCMIOObjectSystemObject), &addr, 0, nil, &size) == 0 else { return [] }
    var ids = [CMIOObjectID](repeating: 0, count: Int(size) / MemoryLayout<CMIOObjectID>.size)
    var used: UInt32 = 0
    CMIOObjectGetPropertyData(CMIOObjectID(kCMIOObjectSystemObject), &addr, 0, nil, size, &used, &ids)
    return ids.map { id in
        var running: UInt32 = 0
        _ = cmioProperty(id, kCMIODevicePropertyDeviceIsRunningSomewhere, &running)
        var name: Unmanaged<CFString>? = nil
        _ = cmioProperty(id, kCMIOObjectPropertyName, &name)
        return ((name?.takeRetainedValue() as String?) ?? "camera", running != 0)
    }
}

func audioIDs(_ object: AudioObjectID, _ selector: AudioObjectPropertySelector,
              _ scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal) -> [AudioObjectID]? {
    var addr = AudioObjectPropertyAddress(mSelector: selector, mScope: scope, mElement: kAudioObjectPropertyElementMain)
    var size: UInt32 = 0
    guard AudioObjectGetPropertyDataSize(object, &addr, 0, nil, &size) == 0 else { return nil }
    var ids = [AudioObjectID](repeating: 0, count: Int(size) / MemoryLayout<AudioObjectID>.size)
    guard AudioObjectGetPropertyData(object, &addr, 0, nil, &size, &ids) == 0 else { return nil }
    return Array(ids.prefix(Int(size) / MemoryLayout<AudioObjectID>.size))
}

func audioFlag(_ object: AudioObjectID, _ selector: AudioObjectPropertySelector) -> Bool {
    var addr = AudioObjectPropertyAddress(mSelector: selector,
        mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
    var value: UInt32 = 0; var size = UInt32(MemoryLayout<UInt32>.size)
    return AudioObjectGetPropertyData(object, &addr, 0, nil, &size, &value) == 0 && value != 0
}

func audioName(_ object: AudioObjectID) -> String {
    var addr = AudioObjectPropertyAddress(mSelector: kAudioObjectPropertyName,
        mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
    var name: Unmanaged<CFString>? = nil; var size = UInt32(MemoryLayout<CFString?>.size)
    AudioObjectGetPropertyData(object, &addr, 0, nil, &size, &name)
    return (name?.takeRetainedValue() as String?) ?? "microphone"
}

// Asked per process, which macOS has offered since 14: a headset is one device for both
// directions, so the device-wide "running somewhere" flag is just as true while AirPods
// only play music. Older systems fall back to that flag, on devices with an input stream.
func microphones() -> [(name: String, running: Bool)] {
    let system = AudioObjectID(kAudioObjectSystemObject)
    if let processes = audioIDs(system, kAudioHardwarePropertyProcessObjectList) {
        var names = Set<String>()
        for process in processes where audioFlag(process, kAudioProcessPropertyIsRunningInput) {
            let devices = audioIDs(process, kAudioProcessPropertyDevices, kAudioObjectPropertyScopeInput) ?? []
            names.formUnion(devices.isEmpty ? ["microphone"] : devices.map(audioName))
        }
        return names.sorted().map { ($0, true) }
    }
    return (audioIDs(system, kAudioHardwarePropertyDevices) ?? []).compactMap { id in
        guard let streams = audioIDs(id, kAudioDevicePropertyStreams, kAudioDevicePropertyScopeInput),
              !streams.isEmpty else { return nil }                 // output-only device
        return (audioName(id), audioFlag(id, kAudioDevicePropertyDeviceIsRunningSomewhere))
    }
}

// MARK: reporting

func log(_ s: String) {
    print("\(ISO8601DateFormatter().string(from: Date())) \(s)")
    fflush(stdout)
}

var reportFailing = false      // log failures once, not every heartbeat, or the log file never stops growing

func post(_ url: URL, _ body: [String: Any]) {
    var req = URLRequest(url: url, timeoutInterval: 5)
    req.httpMethod = "POST"
    req.setValue("application/json", forHTTPHeaderField: "Content-Type")
    req.httpBody = try? JSONSerialization.data(withJSONObject: body)
    let done = DispatchSemaphore(value: 0)
    URLSession.shared.dataTask(with: req) { _, resp, err in
        var failure: String? = nil
        if let err = err { failure = err.localizedDescription }
        else if let code = (resp as? HTTPURLResponse)?.statusCode, code != 200 { failure = "HTTP \(code)" }
        if let f = failure {
            if !reportFailing { log("report failed: \(f) (will keep trying quietly)") }
            reportFailing = true
        } else if reportFailing {
            log("reports reaching Hearth again"); reportFailing = false
        }
        done.signal()
    }.resume()
    _ = done.wait(timeout: .now() + 6)
}

// MARK: main

var args = Array(CommandLine.arguments.dropFirst())
var ignore: Set<String> = []
var once = false
while let i = args.firstIndex(where: { $0.hasPrefix("--") }) {
    let flag = args.remove(at: i)
    if flag == "--once" { once = true }
    else if flag == "--ignore", i < args.count { ignore.insert(args.remove(at: i)) }
}
guard let base = args.first,
      let url = URL(string: base.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/api/busy") else {
    FileHandle.standardError.write("usage: busy-agent <hearth url> [--ignore <device name>]… [--once]\n".data(using: .utf8)!)
    exit(2)
}
let source = Host.current().localizedName ?? "mac"

var micSince: Date? = nil
var lastBusy: Bool? = nil
var lastDevices: [String] = []
var lastSent = Date.distantPast

// A driver that stops answering blocks the query that asked it, for good: a camera
// extension that deadlocked did exactly that. Say so in the log, since nothing is
// reported meanwhile and Hearth drops this Mac after 90 s.
let queryLock = NSLock()
var queryStarted: Date? = nil
var queryStuck = false
let watchdog = DispatchSource.makeTimerSource(queue: .global())
watchdog.schedule(deadline: .now() + 10, repeating: 10)
watchdog.setEventHandler {
    queryLock.lock(); defer { queryLock.unlock() }
    if let started = queryStarted, Date().timeIntervalSince(started) > 20, !queryStuck {
        log("a camera or microphone driver has not answered for 20 s; no reports until it does")
        queryStuck = true
    } else if queryStarted == nil, queryStuck {
        log("drivers answering again")
        queryStuck = false
    }
}
watchdog.resume()

while true {
    queryLock.lock(); queryStarted = Date(); queryLock.unlock()
    let cams = cameras().filter { $0.running && !ignore.contains($0.name) }.map { $0.name }
    let mics = microphones().filter { $0.running && !ignore.contains($0.name) }.map { $0.name }
    queryLock.lock(); queryStarted = nil; queryLock.unlock()
    if mics.isEmpty { micSince = nil } else if micSince == nil { micSince = Date() }
    let micBusy = micSince.map { Date().timeIntervalSince($0) >= MIC_GRACE } ?? false
    let busy = !cams.isEmpty || micBusy
    let devices = cams + (micBusy ? mics : [])

    if once {
        print("cameras in use: \(cams), mics in use: \(mics) → busy=\(busy)")
        post(url, ["source": source, "busy": busy, "devices": devices])
        exit(0)
    }
    let changed = busy != lastBusy || devices != lastDevices
    if changed || Date().timeIntervalSince(lastSent) >= HEARTBEAT {
        if changed { log(busy ? "BUSY (\(devices.joined(separator: ", ")))" : "free") }
        post(url, ["source": source, "busy": busy, "devices": devices])
        lastBusy = busy
        lastDevices = devices
        lastSent = Date()
    }
    // Not Thread.sleep: see the top of the file. run(mode:before:) returns false when the
    // run loop has nothing to wait on, which would make this spin, so sleep then instead.
    let next = Date().addingTimeInterval(POLL)
    while Date() < next {
        if !RunLoop.current.run(mode: .default, before: next) { Thread.sleep(until: next) }
    }
}
