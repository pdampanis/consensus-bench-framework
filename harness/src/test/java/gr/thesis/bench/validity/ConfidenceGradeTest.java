package gr.thesis.bench.validity;

import gr.thesis.bench.validity.ValidityChecker.GateResult;
import gr.thesis.bench.validity.ValidityChecker.Report;
import gr.thesis.bench.validity.ValidityChecker.State;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D13's grade, adapted from GRADE (Guyatt et al., BMJ 2008;336:924–926).
 *
 * The case that matters most is the last one: a VOID run must never grade
 * above VOID however clean its numbers look. F50 and F70 are both real
 * instances of a run that measured beautifully and could not evidence what
 * it claimed, and a grading scheme that reads only the numbers would have
 * stamped both of them A.
 */
class ConfidenceGradeTest {

    private static Report report(boolean valid, State... states) {
        List<GateResult> gates = IntStream.range(0, states.length)
                .mapToObj(i -> new GateResult("gate" + i, states[i], "detail"))
                .toList();
        return new Report(valid, gates);
    }

    private static List<Report> nRuns(int n, Report r) {
        return IntStream.range(0, n).mapToObj(i -> r).toList();
    }

    @Test
    void everyGateEvaluatedAndPassedAtFullRepetitionsGradesA() {
        var grade = ConfidenceGrade.of(
                nRuns(5, report(true, State.PASS, State.PASS)), 0);
        assertEquals(ConfidenceGrade.Level.A, grade.level());
        assertTrue(grade.reasons().isEmpty(), "an A has nothing to caveat");
    }

    @Test
    void anUnevaluatedGateDowngradesToBAndNamesIt() {
        // GRADE calls this indirectness: the claim rests on fewer checks than
        // the methodology specifies. "B" alone would tell a reader nothing
        // about what to be careful of, so the grade names the gate.
        var grade = ConfidenceGrade.of(
                nRuns(5, report(true, State.PASS, State.SKIP)), 0);
        assertEquals(ConfidenceGrade.Level.B, grade.level());
        assertTrue(grade.reasons().get(0).contains("indirectness"), grade.reasons().toString());
        assertTrue(grade.reasons().get(0).contains("gate1"), grade.reasons().toString());
    }

    @Test
    void aFailedGateMakesTheCellAnObservationNotAConclusion() {
        var grade = ConfidenceGrade.of(
                List.of(report(true, State.PASS), report(false, State.FAIL),
                        report(true, State.PASS), report(true, State.PASS),
                        report(true, State.PASS)), 0);
        assertEquals(ConfidenceGrade.Level.C, grade.level());
        assertTrue(grade.reasons().stream().anyMatch(r -> r.contains("risk of bias")),
                grade.reasons().toString());
    }

    @Test
    void missingRepetitionsAreImprecisionNotAnInconvenience() {
        // methodology §3: at n=5 the CIs carry the argument, so losing runs
        // costs the claim directly rather than costing tidiness.
        var grade = ConfidenceGrade.of(nRuns(3, report(true, State.PASS)), 0);
        assertEquals(ConfidenceGrade.Level.C, grade.level());
        assertTrue(grade.reasons().stream()
                        .anyMatch(r -> r.contains("imprecision") && r.contains("3 of 5")),
                grade.reasons().toString());
    }

    @Test
    void aCellOfNothingButVoidRunsIsVOIDNotEmpty() {
        // The F50/F70 class. Both were runs that measured beautifully and
        // could not evidence what they claimed — a scheme reading only the
        // numbers would have graded them A. There is no path from an empty
        // report list to any level above VOID.
        var grade = ConfidenceGrade.of(List.of(), 5);
        assertEquals(ConfidenceGrade.Level.VOID, grade.level());
        assertTrue(grade.reasons().get(0).contains("could not evidence"),
                grade.reasons().toString());
    }

    @Test
    void voidRunsAlongsideGoodOnesStillDowngradeTheCell() {
        // Four clean runs and one that could not evidence itself is not a
        // clean cell with a footnote: something in the campaign misbehaved,
        // and the reader has to know before quoting the number.
        var grade = ConfidenceGrade.of(nRuns(4, report(true, State.PASS)), 1);
        assertEquals(ConfidenceGrade.Level.C, grade.level());
        assertTrue(grade.reasons().stream().anyMatch(r -> r.contains("VOID")),
                grade.reasons().toString());
    }

    @Test
    void theOrderingIsUsableForSortingCellsByTrustworthiness() {
        // C6's stated test: "can I sort cells by trustworthiness?"
        assertTrue(ConfidenceGrade.Level.A.compareTo(ConfidenceGrade.Level.B) > 0);
        assertTrue(ConfidenceGrade.Level.B.compareTo(ConfidenceGrade.Level.C) > 0);
        assertTrue(ConfidenceGrade.Level.C.compareTo(ConfidenceGrade.Level.VOID) > 0);
    }
}
