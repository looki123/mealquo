import Foundation
import HealthKit

// „Pobierz z zegarka”: treningi danego dnia z Apple Health, ten sam format co Watch.kt na Androidzie
final class Health {
    private let store = HKHealthStore()

    func read(day: String, done: @escaping (String) -> Void) {
        guard HKHealthStore.isHealthDataAvailable() else {
            done("window.onWatchStatus && window.onWatchStatus('missing')")
            return
        }
        let types: Set<HKObjectType> = [HKObjectType.workoutType(), HKQuantityType(.activeEnergyBurned)]
        store.requestAuthorization(toShare: nil, read: types) { ok, _ in
            guard ok else {
                done("window.onWatchStatus && window.onWatchStatus('denied')")
                return
            }
            let format = DateFormatter()
            format.locale = Locale(identifier: "en_US_POSIX")
            format.calendar = Calendar(identifier: .gregorian)
            format.timeZone = .current
            format.dateFormat = "yyyy-MM-dd"
            let start = format.date(from: day) ?? Calendar.current.startOfDay(for: Date())
            let end = Calendar.current.date(byAdding: .day, value: 1, to: start) ?? start
            let range = HKQuery.predicateForSamples(withStart: start, end: end)
            let query = HKSampleQuery(sampleType: .workoutType(), predicate: range, limit: 50, sortDescriptors: nil) { _, samples, error in
                if error != nil {
                    done("window.onWatchStatus && window.onWatchStatus('fail')")
                    return
                }
                var items: [[String: Any]] = []
                for case let w as HKWorkout in samples ?? [] {
                    let kcal = w.statistics(for: HKQuantityType(.activeEnergyBurned))?.sumQuantity()?.doubleValue(for: .kilocalorie()) ?? 0
                    items.append(["id": w.uuid.uuidString, "name": Health.name(w.workoutActivityType),
                                  "kcal": Int(kcal.rounded()), "min": Int((w.duration / 60).rounded()), "date": day])
                }
                let data = (try? JSONSerialization.data(withJSONObject: ["items": items])) ?? Data("{\"items\":[]}".utf8)
                done("window.onWatch && window.onWatch('\(data.base64EncodedString())')")
            }
            self.store.execute(query)
        }
    }

    // kroki dnia (iPhone liczy je sam) -> window.onSteps(dzień, liczba, status)
    func steps(day: String, done: @escaping (String) -> Void) {
        let q = { (s: String) in "'" + s.replacingOccurrences(of: "'", with: "") + "'" }
        guard HKHealthStore.isHealthDataAvailable() else { done("window.onSteps && window.onSteps(\(q(day)),-1,'missing')"); return }
        let type = HKQuantityType(.stepCount)
        store.requestAuthorization(toShare: nil, read: [type]) { ok, _ in
            guard ok else { done("window.onSteps && window.onSteps(\(q(day)),-1,'noperm')"); return }
            let format = DateFormatter()
            format.locale = Locale(identifier: "en_US_POSIX")
            format.calendar = Calendar(identifier: .gregorian)
            format.timeZone = .current
            format.dateFormat = "yyyy-MM-dd"
            let start = format.date(from: day) ?? Calendar.current.startOfDay(for: Date())
            let end = Calendar.current.date(byAdding: .day, value: 1, to: start) ?? start
            let range = HKQuery.predicateForSamples(withStart: start, end: end)
            let query = HKStatisticsQuery(quantityType: type, quantitySamplePredicate: range, options: .cumulativeSum) { _, stats, error in
                if error != nil { done("window.onSteps && window.onSteps(\(q(day)),-1,'fail')"); return }
                let n = Int(stats?.sumQuantity()?.doubleValue(for: .count()) ?? 0)
                done("window.onSteps && window.onSteps(\(q(day)),\(n),'ok')")
            }
            self.store.execute(query)
        }
    }

    static func name(_ type: HKWorkoutActivityType) -> String {
        switch type {
        case .running: return "Bieganie"
        case .walking: return "Spacer"
        case .cycling: return "Rower"
        case .traditionalStrengthTraining, .functionalStrengthTraining: return "Siłownia"
        case .swimming: return "Pływanie"
        case .yoga: return "Joga"
        case .pilates: return "Pilates"
        case .highIntensityIntervalTraining: return "HIIT"
        case .boxing: return "Boks"
        case .socialDance, .cardioDance: return "Taniec"
        case .hiking: return "Hiking"
        case .rowing: return "Wioślarz"
        case .elliptical: return "Orbitrek"
        case .stairClimbing, .stairs: return "Schody"
        default: return "Trening"
        }
    }
}
