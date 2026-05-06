import { useState } from "react";
import AlgorithmView from "./views/AlgorithmView";
import ResultsView from "./views/ResultsView";
import CompareView from "./views/CompareView";

type Tab = "results" | "compare" | "algorithms";

/**
 * Top-level layout. Three primary tabs:
 *
 *  Results    - pick a (system, scenario) and see all runs, aggregated
 *               statistics, and per-metric time-series.
 *  Compare    - side-by-side aggregation across every system for one
 *               scenario (the headline cross-system comparison).
 *  Algorithms - animated network-view of each consensus algorithm, with
 *               scripted scenarios for normal operation, leader election,
 *               and fault handling.
 */
export default function App() {
  const [tab, setTab] = useState<Tab>("results");

  return (
    <div className="app">
      <header className="topbar">
        <h1>Consensus Benchmark Visualizer</h1>
        <span style={{ color: "var(--muted)", fontSize: 13 }}>
          thesis results explorer & algorithm animator
        </span>
      </header>

      <nav className="tabs">
        <button className={tab === "results" ? "active" : ""} onClick={() => setTab("results")}>
          Results
        </button>
        <button className={tab === "compare" ? "active" : ""} onClick={() => setTab("compare")}>
          Compare
        </button>
        <button className={tab === "algorithms" ? "active" : ""} onClick={() => setTab("algorithms")}>
          Algorithms
        </button>
      </nav>

      <main>
        {tab === "results" && <ResultsView />}
        {tab === "compare" && <CompareView />}
        {tab === "algorithms" && <AlgorithmView />}
      </main>
    </div>
  );
}
