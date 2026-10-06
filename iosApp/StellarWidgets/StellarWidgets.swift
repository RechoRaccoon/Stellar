import SwiftUI
import WidgetKit

// Stellar's home-screen widgets (a supporter benefit): your DMs with their
// unread counts, your calendar's upcoming events, and one note in full.
// The app writes what they show into the app group's shared defaults (see
// IosWidgetBridge in the Kotlin code); this extension only reads it.

private let groupId = "group.rechoraccoon.stellar"
private let stellarPink = Color(red: 1.0, green: 0.31, blue: 0.63)

// MARK: - Data

struct WidgetChat: Decodable, Identifiable {
    let id: String
    let name: String
    let text: String
    let unread: Int
    let avatar: String
    var streak: Int?
    var group: Bool?
}

struct WidgetEvent: Decodable, Identifiable {
    let day: Int
    let minute: Int
    let title: String
    var id: String { "\(day)-\(minute)-\(title)" }
}

struct WidgetNote: Decodable {
    let id: String
    let title: String
    let body: String
}

struct StellarSnapshot {
    var supporter = false
    var chats: [WidgetChat] = []
    var events: [WidgetEvent] = []
    var note: WidgetNote?
    var colorA = Color(red: 0.13, green: 0.15, blue: 0.36)
    var colorB = Color(red: 0.08, green: 0.09, blue: 0.21)

    static func load() -> StellarSnapshot {
        var s = StellarSnapshot()
        guard let defaults = UserDefaults(suiteName: groupId) else { return s }
        s.supporter = defaults.bool(forKey: "supporter")
        let decoder = JSONDecoder()
        if let data = defaults.string(forKey: "chats")?.data(using: .utf8),
           let chats = try? decoder.decode([WidgetChat].self, from: data) {
            s.chats = chats
        }
        if let data = defaults.string(forKey: "events")?.data(using: .utf8),
           let events = try? decoder.decode([WidgetEvent].self, from: data) {
            s.events = events.filter { $0.day >= todayKey() }
        }
        if let data = defaults.string(forKey: "note")?.data(using: .utf8),
           let note = try? decoder.decode(WidgetNote.self, from: data) {
            s.note = note
        }
        if let data = defaults.string(forKey: "colors")?.data(using: .utf8),
           let colors = try? decoder.decode([[Double]].self, from: data),
           colors.count == 2, colors[0].count == 3, colors[1].count == 3 {
            s.colorA = Color(red: colors[0][0], green: colors[0][1], blue: colors[0][2])
            s.colorB = Color(red: colors[1][0], green: colors[1][1], blue: colors[1][2])
        }
        return s
    }
}

private func todayKey(_ date: Date = Date()) -> Int {
    let c = Calendar.current.dateComponents([.year, .month, .day], from: date)
    return (c.year ?? 1970) * 10000 + (c.month ?? 1) * 100 + (c.day ?? 1)
}

private func dayLabel(_ day: Int) -> String {
    if day == todayKey() { return "Today" }
    if let tomorrow = Calendar.current.date(byAdding: .day, value: 1, to: Date()), day == todayKey(tomorrow) { return "Tomorrow" }
    let months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
    let m = max(1, min(12, day / 100 % 100))
    return "\(months[m - 1]) \(day % 100)"
}

private func dayNumber(_ day: Int) -> Int {
    var c = DateComponents()
    c.year = day / 10000
    c.month = day / 100 % 100
    c.day = day % 100
    let cal = Calendar.current
    guard let date = cal.date(from: c) else { return 0 }
    return cal.dateComponents([.day], from: cal.startOfDay(for: Date(timeIntervalSince1970: 0)), to: cal.startOfDay(for: date)).day ?? 0
}

/// "Today", "In 1 Day", "In 12 Days".
private func countdownLabel(_ day: Int) -> String {
    let diff = dayNumber(day) - dayNumber(todayKey())
    if diff <= 0 { return "Today" }
    return diff == 1 ? "In 1 Day" : "In \(diff) Days"
}

private func timeLabel(_ minute: Int) -> String {
    if minute < 0 { return "All day" }
    let h = minute / 60
    let m = minute % 60
    let h12 = h % 12 == 0 ? 12 : h % 12
    return "\(h12):\(m < 10 ? "0" : "")\(m) \(h < 12 ? "AM" : "PM")"
}

// MARK: - Timeline

struct StellarEntry: TimelineEntry {
    let date: Date
    let snapshot: StellarSnapshot
}

struct StellarProvider: TimelineProvider {
    func placeholder(in context: Context) -> StellarEntry { StellarEntry(date: Date(), snapshot: StellarSnapshot()) }

    func getSnapshot(in context: Context, completion: @escaping (StellarEntry) -> Void) {
        completion(StellarEntry(date: Date(), snapshot: StellarSnapshot.load()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<StellarEntry>) -> Void) {
        // The app asks for a redraw whenever something changes; this is
        // only so "Today" / "Tomorrow" stay right while it's closed.
        let next = Calendar.current.date(byAdding: .minute, value: 30, to: Date()) ?? Date().addingTimeInterval(1800)
        completion(Timeline(entries: [StellarEntry(date: Date(), snapshot: StellarSnapshot.load())], policy: .after(next)))
    }
}

// MARK: - Shared look

/// The bubble: your two profile colors (deepened so white text reads on
/// anything), with a bright rim in the same hue.
private struct StellarBackground: View {
    let snapshot: StellarSnapshot
    var body: some View {
        ZStack {
            LinearGradient(colors: [snapshot.colorA, snapshot.colorB], startPoint: .topLeading, endPoint: .bottomTrailing)
            LinearGradient(colors: [Color.black.opacity(0.5), Color.black.opacity(0.68)], startPoint: .topLeading, endPoint: .bottomTrailing)
        }
    }
}

private extension View {
    @ViewBuilder
    func stellarWidgetBackground(_ snapshot: StellarSnapshot) -> some View {
        if #available(iOS 17.0, *) {
            self.containerBackground(for: .widget) { StellarBackground(snapshot: snapshot) }
        } else {
            self.padding(12).background(StellarBackground(snapshot: snapshot))
        }
    }
}

private struct WidgetHeader: View {
    let title: String
    var badge: Int = 0
    var body: some View {
        HStack(spacing: 6) {
            Text(title)
                .font(.custom("Audiowide-Regular", size: 13))
                .foregroundColor(.white)
                .lineLimit(1)
            Spacer(minLength: 0)
            if badge > 0 {
                Text(badge > 99 ? "99+" : "\(badge)")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 6)
                    .frame(minWidth: 18, minHeight: 18)
                    .background(Capsule().fill(stellarPink))
            }
        }
    }
}

private struct RowBackground: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .background(RoundedRectangle(cornerRadius: 13, style: .continuous).fill(Color.black.opacity(0.2)))
    }
}

private struct EmptyNote: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.system(size: 12))
            .foregroundColor(.white.opacity(0.7))
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private func rowCount(_ family: WidgetFamily, small: Int, medium: Int, large: Int) -> Int {
    switch family {
    case .systemSmall: return small
    case .systemMedium: return medium
    default: return large
    }
}

// MARK: - DMs

struct DmsWidgetView: View {
    @Environment(\.widgetFamily) var family
    let entry: StellarEntry

    var body: some View {
        let s = entry.snapshot
        let chats = Array(s.chats.prefix(rowCount(family, small: 2, medium: 2, large: 6)))
        VStack(alignment: .leading, spacing: 6) {
            Link(destination: URL(string: "stellar://dms")!) {
                WidgetHeader(title: "DMs", badge: s.supporter ? s.chats.reduce(0) { $0 + $1.unread } : 0)
            }
            if !s.supporter {
                EmptyNote(text: "A Stellar Supporter benefit.")
            } else if chats.isEmpty {
                EmptyNote(text: "Open Stellar to load your chats.")
            } else {
                ForEach(chats) { chat in
                    Link(destination: URL(string: "stellar://dm/\(chat.id)")!) {
                        HStack(spacing: 8) {
                            ZStack {
                                Circle().fill(Color.white.opacity(0.22))
                                Text(String(chat.name.prefix(1)).uppercased())
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundColor(.white)
                            }
                            .frame(width: 28, height: 28)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(chat.name)
                                    .font(.system(size: 13, weight: .semibold))
                                    .foregroundColor(.white)
                                    .lineLimit(1)
                                if family != .systemSmall {
                                    Text(chat.text)
                                        .font(.system(size: 11))
                                        .foregroundColor(.white.opacity(0.78))
                                        .lineLimit(1)
                                }
                            }
                            Spacer(minLength: 0)
                            if let streak = chat.streak, streak > 0 {
                                Text("\u{1F525} \(streak)")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundColor(.white)
                                    .lineLimit(1)
                            }
                            if chat.unread > 0 {
                                Text(chat.unread > 99 ? "99+" : "\(chat.unread)")
                                    .font(.system(size: 10, weight: .bold))
                                    .foregroundColor(.white)
                                    .padding(.horizontal, 5)
                                    .frame(minWidth: 18, minHeight: 18)
                                    .background(Capsule().fill(stellarPink))
                            }
                        }
                        .modifier(RowBackground())
                    }
                }
                Spacer(minLength: 0)
            }
        }
        .widgetURL(URL(string: "stellar://dms"))
        .stellarWidgetBackground(s)
    }
}

struct DmsWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "StellarDms", provider: StellarProvider()) { entry in
            DmsWidgetView(entry: entry)
        }
        .configurationDisplayName("DMs")
        .description("Your chats and unread messages.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

// MARK: - Upcoming Events

struct EventsWidgetView: View {
    @Environment(\.widgetFamily) var family
    let entry: StellarEntry

    var body: some View {
        let s = entry.snapshot
        let events = Array(s.events.prefix(rowCount(family, small: 2, medium: 2, large: 6)))
        VStack(alignment: .leading, spacing: 6) {
            WidgetHeader(title: family == .systemSmall ? "Events" : "Upcoming Events")
            if !s.supporter {
                EmptyNote(text: "A Stellar Supporter benefit.")
            } else if events.isEmpty {
                EmptyNote(text: "Nothing coming up.")
            } else {
                ForEach(events) { event in
                    // A tap opens the Calendar on this event's day.
                    Link(destination: URL(string: "stellar://calendar/\(event.day)")!) {
                    HStack(spacing: 8) {
                        Text(dayLabel(event.day))
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(.white)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                            .frame(width: 52)
                            .padding(.vertical, 5)
                            .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(Color.white.opacity(0.24)))
                        VStack(alignment: .leading, spacing: 1) {
                            Text(event.title)
                                .font(.system(size: 13, weight: .medium))
                                .foregroundColor(.white)
                                .lineLimit(1)
                            Text(timeLabel(event.minute))
                                .font(.system(size: 11))
                                .foregroundColor(.white.opacity(0.78))
                                .lineLimit(1)
                        }
                        Spacer(minLength: 0)
                        if family != .systemSmall {
                            Text(countdownLabel(event.day))
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(.white.opacity(0.9))
                                .lineLimit(1)
                        }
                    }
                    .modifier(RowBackground())
                    }
                }
                Spacer(minLength: 0)
            }
        }
        .widgetURL(URL(string: "stellar://calendar"))
        .stellarWidgetBackground(s)
    }
}

struct EventsWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "StellarEvents", provider: StellarProvider()) { entry in
            EventsWidgetView(entry: entry)
        }
        .configurationDisplayName("Upcoming Events")
        .description("Your calendar's next events.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

// MARK: - Note

/// One line of a note, with the markdown it starts with turned into how it
/// reads (headings, bullets, ticked / unticked boxes, quotes).
private struct NoteLine: View {
    let raw: String

    var body: some View {
        let line = raw.trimmingCharacters(in: .whitespaces)
        if line.hasPrefix("### ") { styled(String(line.dropFirst(4)), size: 14, bold: true) }
        else if line.hasPrefix("## ") { styled(String(line.dropFirst(3)), size: 16, bold: true) }
        else if line.hasPrefix("# ") { styled(String(line.dropFirst(2)), size: 18, bold: true) }
        else if line.hasPrefix("- [ ] ") || line.hasPrefix("* [ ] ") { styled("☐  " + String(line.dropFirst(6)), size: 13, bold: false) }
        else if line.lowercased().hasPrefix("- [x] ") || line.lowercased().hasPrefix("* [x] ") {
            styled("☑  " + String(line.dropFirst(6)), size: 13, bold: false).opacity(0.6)
        }
        else if line.hasPrefix("- ") || line.hasPrefix("* ") { styled("•  " + String(line.dropFirst(2)), size: 13, bold: false) }
        else if line.hasPrefix("> ") { styled("▎ " + String(line.dropFirst(2)), size: 13, bold: false).opacity(0.8) }
        else { styled(line, size: 13, bold: false) }
    }

    private func styled(_ text: String, size: CGFloat, bold: Bool) -> some View {
        let clean = text.replacingOccurrences(of: "**", with: "").replacingOccurrences(of: "~~", with: "").replacingOccurrences(of: "`", with: "")
        return Text(clean)
            .font(.system(size: size, weight: bold ? .bold : .regular))
            .foregroundColor(.white.opacity(0.95))
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct NoteWidgetView: View {
    let entry: StellarEntry

    var body: some View {
        let s = entry.snapshot
        let note = s.note
        let lines = (note?.body ?? "").components(separatedBy: "\n").filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("![") }
        VStack(alignment: .leading, spacing: 5) {
            WidgetHeader(title: (note?.title.isEmpty == false ? note!.title : "Note"))
            if !s.supporter {
                EmptyNote(text: "A Stellar Supporter benefit.")
            } else if note == nil {
                EmptyNote(text: "No notes yet.")
            } else {
                // As much of the note as fits; the rest is cut off cleanly.
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(lines.prefix(40).enumerated()), id: \.offset) { _, line in
                        if line.trimmingCharacters(in: .whitespaces).isEmpty { Spacer().frame(height: 4) } else { NoteLine(raw: line) }
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .clipped()
            }
        }
        .widgetURL(URL(string: note != nil ? "stellar://note/\(note!.id)" : "stellar://notes"))
        .stellarWidgetBackground(s)
    }
}

struct NoteWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "StellarNote", provider: StellarProvider()) { entry in
            NoteWidgetView(entry: entry)
        }
        .configurationDisplayName("Note")
        .description("One of your notes, in full.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

@main
struct StellarWidgetBundle: WidgetBundle {
    var body: some Widget {
        DmsWidget()
        EventsWidget()
        NoteWidget()
    }
}
