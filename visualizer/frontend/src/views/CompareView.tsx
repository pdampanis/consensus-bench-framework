import { useEffect, useMemo, useState } from "react";
import { api, CompareAcrossSystems } from "../api";
import {
  Bar,
  BarChart,
  CartesianGrid,
  ErrorBar,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

/**
 * Cross-system comparison for a single scenario. Produces the headline
 * figures the thesis text cites directly: one bar chart of throughput
 * across every system for the chosen scenario, with 95% CI error bars.
 */
export default function CompareView() {
  const [scenario, setScenario] = useState<string>("baseline");
  const [data, setData] = useState<CompareAcrossSystems | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .compareAcrossSystems(scenario)
      .then(setData)
      .catch((e) => setError(String(e)));
  }, [scenario]);

  const chartRows = useMemo(() => {
    if (!data) return [];
    return Object.entries(data.systems).map(([sys, stats]) => ({
      system: sys,
      throughput: stats.throughputOps.mean,
      tputErrLow: stats.throughputOps.mean - stats.throughputOps.ci95Low,
      tputErrHigh: stats.throughputOps.ci95High - stats.throughputOps.mean,
      p99: stats.writeP99Us.mean / 1000,
      p99ErrLow: (stats.writeP99Us.mean - stats.writeP99Us.ci95Low) / 1000,
      p99ErrHigh: (stats.writeP99Us.ci95High - stats.writeP99Us.mean) / 1000,
      nRuns: stats.nRuns,
    }));
  }, [data]);

  return (
    <>
      {error && (
        <div className="panel" style={{ color: "var(--err)" }}>
          {error}
        </div>
      )}

      <div className="panel">
        <label>
          Scenario:&nbsp;
          <select value={scenario} onChange={(e) => setScenario(e.target.value)}>
            <option value="baseline">baseline</option>
            <option value="packet_loss">packet_loss</option>
            <option value="leader_kill">leader_kill</option>
            <option value="dos">dos</option>
            <option value="network_partition">network_partition</option>
          </select>
        </label>
        <span style={{ marginLeft: 16, color: "var(--muted)" }}>
          Error bars are 95% bootstrap confidence intervals.
        </span>
      </div>

      <div className="grid-2">
        <div className="panel">
          <h2>Throughput by system (mean ± 95% CI)</h2>
          <ResponsiveContainer width="100%" height={320}>
            <BarChart data={chartRows}>
              <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
              <XAxis dataKey="system" stroke="#94a3b8" />
              <YAxis stroke="#94a3b8" />
              <Tooltip contentStyle={{ background: "#1e293b", border: "1px solid #334155" }} />
              <Legend />
              <Bar dataKey="throughput" fill="var(--accent)">
                <ErrorBar dataKey="tputErrHigh" width={4} strokeWidth={2} stroke="#f8fafc" />
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>

        <div className="panel">
          <h2>p99 write latency by system (ms)</h2>
          <ResponsiveContainer width="100%" height={320}>
            <BarChart data={chartRows}>
              <CartesianGrid strokeDasharray="3 3" stroke="#334155" />
              <XAxis dataKey="system" stroke="#94a3b8" />
              <YAxis stroke="#94a3b8" />
              <Tooltip contentStyle={{ background: "#1e293b", border: "1px solid #334155" }} />
              <Legend />
              <Bar dataKey="p99" fill="#facc15">
                <ErrorBar dataKey="p99ErrHigh" width={4} strokeWidth={2} stroke="#f8fafc" />
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
      </div>

      <div className="panel">
        <h2>Numerical summary</h2>
        <table style={{ width: "100%", fontSize: 13, borderCollapse: "collapse" }}>
          <thead>
            <tr style={{ color: "var(--muted)" }}>
              <th style={{ textAlign: "left", padding: 4 }}>System</th>
              <th style={{ textAlign: "right", padding: 4 }}>n</th>
              <th style={{ textAlign: "right", padding: 4 }}>Throughput mean</th>
              <th style={{ textAlign: "right", padding: 4 }}>CI 95%</th>
              <th style={{ textAlign: "right", padding: 4 }}>p99 mean (ms)</th>
              <th style={{ textAlign: "right", padding: 4 }}>CI 95%</th>
            </tr>
          </thead>
          <tbody>
            {chartRows.map((r) => (
              <tr key={r.system} style={{ borderTop: "1px solid #334155" }}>
                <td style={{ padding: 4 }}>{r.system}</td>
                <td style={{ padding: 4, textAlign: "right" }}>{r.nRuns}</td>
                <td style={{ padding: 4, textAlign: "right" }}>{r.throughput.toFixed(0)}</td>
                <td style={{ padding: 4, textAlign: "right", color: "var(--muted)" }}>
                  ±{Math.max(r.tputErrLow, r.tputErrHigh).toFixed(0)}
                </td>
                <td style={{ padding: 4, textAlign: "right" }}>{r.p99.toFixed(2)}</td>
                <td style={{ padding: 4, textAlign: "right", color: "var(--muted)" }}>
                  ±{Math.max(r.p99ErrLow, r.p99ErrHigh).toFixed(2)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
