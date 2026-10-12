// busy-agent — tells Hearth when this Mac is on a call.
//
// Polls Core Media I/O (cameras) and Core Audio (microphones) for devices that are
// "running somewhere", i.e. in use by any app, and POSTs {source, busy, devices} to
// <hearth>/api/busy on every change plus a heartbeat every 30 s. Hearth ignores a
// report that stops checking in, so a sleeping Mac can't leave the banner stuck on.
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

func microphones() -> [(name: String, running: Bool)] {
    var addr = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDevices,
        mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
    var size: UInt32 = 0
    guard AudioObjectGetPropertyDataSize(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size) == 0 else { return [] }
    var ids = [AudioObjectID](repeating: 0, count: Int(size) / MemoryLayout<AudioObjectID>.size)
    AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size, &ids)
    return ids.compactMap { id in
        var streams = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyStreams,
            mScope: kAudioDevicePropertyScopeInput, mElement: kAudioObjectPropertyElementMain)
        var n: UInt32 = 0
        AudioObjectGetPropertyDataSize(id, &streams, 0, nil, &n)
        if n == 0 { return nil }                                   // output-only device
        var runAddr = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyDeviceIsRunningSomewhere,
            mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var running: UInt32 = 0; var rs = UInt32(MemoryLayout<UInt32>.size)
        AudioObjectGetPropertyData(id, &runAddr, 0, nil, &rs, &running)
        var nameAddr = AudioObjectPropertyAddress(mSelector: kAudioObjectPropertyName,
            mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var name: Unmanaged<CFString>? = nil; var ns = UInt32(MemoryLayout<CFString?>.size)
        AudioObjectGetPropertyData(id, &nameAddr, 0, nil, &ns, &name)
        return ((name?.takeRetainedValue() as String?) ?? "microphone", running != 0)
    }
}

// MARK: reporting

func log(_ s: String) {
    print("\(ISO8601DateFormatter().string(from: Date())) \(s)")
    fflush(stdout)
}

func post(_ url: URL, _ body: [String: Any]) {
    var req = URLRequest(url: url, timeoutInterval: 5)
    req.httpMethod = "POST"
    req.setValue("application/json", forHTTPHeaderField: "Content-Type")
    req.httpBody = try? JSONSerialization.data(withJSONObject: body)
    let done = DispatchSemaphore(value: 0)
    URLSession.shared.dataTask(with: req) { _, resp, err in
        if let err = err { log("report failed: \(err.localizedDescription)") }
        else if let code = (resp as? HTTPURLResponse)?.statusCode, code != 200 { log("report got HTTP \(code)") }
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
var lastSent = Date.distantPast

while true {
    let cams = cameras().filter { $0.running && !ignore.contains($0.name) }.map { $0.name }
    let mics = microphones().filter { $0.running && !ignore.contains($0.name) }.map { $0.name }
    if mics.isEmpty { micSince = nil } else if micSince == nil { micSince = Date() }
    let micBusy = micSince.map { Date().timeIntervalSince($0) >= MIC_GRACE } ?? false
    let busy = !cams.isEmpty || micBusy
    let devices = cams + (micBusy ? mics : [])

    if once {
        print("cameras in use: \(cams), mics in use: \(mics) → busy=\(busy)")
        post(url, ["source": source, "busy": busy, "devices": devices])
        exit(0)
    }
    if busy != lastBusy || Date().timeIntervalSince(lastSent) >= HEARTBEAT {
        if busy != lastBusy { log(busy ? "BUSY (\(devices.joined(separator: ", ")))" : "free") }
        post(url, ["source": source, "busy": busy, "devices": devices])
        lastBusy = busy
        lastSent = Date()
    }
    Thread.sleep(forTimeInterval: POLL)
}
