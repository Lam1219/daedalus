import XCTest

/// The same rule cases as daedalus_watch_kit/tests (test_fog.py, test_tremor_stream.py) for fog-1.1.
final class DetectorRulesTest: XCTestCase {

    private func win(_ sec: Int, _ ctx: Context, reg: Double = 0.9, fi: Double = 0.3,
                     swing: Double = 60, grav: Double = 0) -> WindowFeatures {
        WindowFeatures(tEndMs: Int64(sec) * 1000, tremorDps: 0, peakHz: 5, prominence: 1, slowDps: 30,
                       motionDps: 40, meanRotationDps: 0, accStd: 1, gravityChangeDeg: grav,
                       freezeIndex: fi, cadenceSpm: 100, stepRegularity: reg, armSwingDps: swing, context: ctx)
    }

    private func walk(_ from: Int, _ until: Int, _ ctx: Context = .walking) -> [WindowFeatures] {
        (from..<until).map { win($0, ctx) }
    }

    func testStillSecondBreaksARunOfFreezeSeconds() {
        let ws = walk(4, 12) + walk(12, 14, .turning) + [
            win(14, .activeHand, reg: 0.1, fi: 6),
            win(15, .still, reg: 0.1, fi: 0.2, swing: 1),
            win(16, .activeHand, reg: 0.1, fi: 6),
        ]
        XCTAssertEqual(FogDetector.detect(ws).count, 0)
    }

    func testWalkContinuesOverAOneSecondDropout() {
        let ws = walk(4, 10) + [win(10, .activeHand, reg: 0.6, fi: 0.5)] + walk(11, 13) + [win(13, .activeHand, reg: 0.1, fi: 6)]
        let c = FogDetector.detect(ws)
        XCTAssertEqual(c.count, 1)
        XCTAssertEqual(c.first?.walkStartMs, 0)
    }

    func testFeedbackBoundsNeverInvert() {
        let p = TremorStream.afterFeedback(TremorStream.Params(thresholdDps: 1), falsePositive: true, baseDps: 0.8)
        XCTAssertEqual(p.thresholdDps, TremorStream.tMin, accuracy: 1e-12)
    }
}
