package gr.thesis.bench.validity;

import java.util.ArrayList;
import java.util.List;

/**
 * D13 — how far a measured cell can be trusted, as an ORDINAL grade with
 * NAMED reasons, never a numeric score.
 *
 * <p>Adapted from <b>GRADE</b> (Guyatt et al., BMJ 2008;336:924–926), which
 * rates a body of evidence on ordinal levels and downgrades for stated
 * reasons — imprecision, inconsistency, indirectness, risk of bias. Two
 * things are taken from it rather than merely cited:
 *
 * <ul>
 *   <li><b>The grade attaches to a CELL, never to one run.</b> GRADE is
 *       explicit that quality is a property of a BODY of evidence. Our body
 *       is the cell (n = 5 repetitions); a single run only contributes to
 *       it.</li>
 *   <li><b>Downgrade reasons use GRADE's vocabulary</b>, so the thesis can
 *       state the mapping instead of inventing one: <i>imprecision</i> ← too
 *       few valid runs to estimate; <i>risk of bias</i> ← a FAILed gate;
 *       <i>indirectness</i> ← gates that could not be evaluated at all.</li>
 * </ul>
 *
 * <p><b>VOID is our addition.</b> GRADE has no level for "this evidence
 * cannot evidence its own claim", because that failure mode does not arise
 * in a literature review. It arises here twice over — F50's fault run whose
 * fault never fired, and F70's overflowed event log — and both would
 * otherwise present as pristine data.
 *
 * <p>Publication bias, GRADE's fifth reason, has no analogue in a
 * single-author campaign that reports every cell. The thesis says so rather
 * than dropping it silently.
 */
public final class ConfidenceGrade {

    /** Ordinal, worst to best. The letters are shorthand for WHICH criteria
     *  were met and always ship with that list — a grade without its reasons
     *  is the numeric score D13 rejected, wearing a letter. */
    public enum Level {
        /** The cell cannot evidence what it claims (F50/F70 class). */
        VOID,
        /** A gate FAILed, or too few runs to estimate — observation only. */
        C,
        /** Everything evaluated PASSed, but some gates could not be evaluated. */
        B,
        /** Every applicable gate evaluated and passed, at full repetitions. */
        A
    }

    /** @param reasons GRADE-vocabulary downgrade reasons, in the order applied */
    public record Grade(Level level, List<String> reasons) {
        public Grade {
            reasons = List.copyOf(reasons);
        }
    }

    /** methodology §1: n = 5 repetitions per cell. */
    static final int EXPECTED_REPETITIONS = 5;

    private ConfidenceGrade() { }

    /**
     * Grade one cell from the validity reports of the runs in it.
     *
     * @param reports one per run in the cell, in any order
     * @param voidRuns runs whose manifest says {@code status != complete} —
     *                 counted separately because a VOID run is not a failing
     *                 measurement, it is an absent one
     */
    public static Grade of(List<ValidityChecker.Report> reports, int voidRuns) {
        List<String> reasons = new ArrayList<>();

        if (reports.isEmpty()) {
            reasons.add("no valid run in this cell — nothing to estimate from"
                    + (voidRuns > 0 ? " (" + voidRuns + " run(s) could not evidence"
                            + " their own claim)" : ""));
            return new Grade(Level.VOID, reasons);
        }

        long failing = reports.stream().filter(r -> !r.valid()).count();
        if (failing > 0) {
            // GRADE: risk of bias. A FAILed gate means a stated precondition
            // of the measurement did not hold, so the number may be an
            // artifact of the environment rather than of the protocol.
            reasons.add("risk of bias: " + failing + " of " + reports.size()
                    + " run(s) failed a validity gate");
        }
        if (voidRuns > 0) {
            reasons.add("risk of bias: " + voidRuns
                    + " run(s) were VOID (could not evidence their own claim)");
        }
        if (reports.size() < EXPECTED_REPETITIONS) {
            // GRADE: imprecision. With fewer repetitions the interval widens
            // and, at n=5, methodology §3 already says the CIs carry the
            // argument — so losing runs costs the claim directly.
            reasons.add("imprecision: " + reports.size() + " of " + EXPECTED_REPETITIONS
                    + " repetitions available");
        }
        if (failing > 0 || reports.size() < EXPECTED_REPETITIONS) {
            return new Grade(Level.C, reasons);
        }

        // Everything that ran, passed. What could NOT run decides A vs B.
        List<String> skipped = reports.get(0).gates().stream()
                .filter(g -> g.state() == ValidityChecker.State.SKIP)
                .map(ValidityChecker.GateResult::gate)
                .distinct().sorted().toList();
        if (!skipped.isEmpty()) {
            // GRADE: indirectness. The claim rests on fewer checks than the
            // methodology specifies, so it is supported less directly — and
            // the grade NAMES which, because "B" alone tells a reader nothing
            // about what to be careful of.
            reasons.add("indirectness: gate(s) not evaluated — " + String.join(", ", skipped));
            return new Grade(Level.B, reasons);
        }
        return new Grade(Level.A, List.of());
    }
}
