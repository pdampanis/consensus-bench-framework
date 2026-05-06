import { useEffect, useRef, useState } from "react";
import { api, AlgorithmSpec, AlgorithmScenario } from "../api";
import NetworkCanvas from "../components/NetworkCanvas";

/**
 * Per-algorithm animated visualization.
 *
 * Left column: list of algorithms as tabs (Raft, KRaft, ZAB, EPaxos,
 * Tendermint, HotStuff). Selecting one loads its spec from the backend.
 *
 * Right column: the animated network view plus a scenario picker. Each
 * algorithm defines several scenarios (normal operation, leader election,
 * faulty nodes, etc.); picking one runs the scripted animation.
 */
export default function AlgorithmView() {
  const [algos, setAlgos] = useState<string[]>([]);
  const [selected, setSelected] = useState<string>("");
  const [spec, setSpec] = useState<AlgorithmSpec | null>(null);
  const [scenario, setScenario] = useState<AlgorithmScenario | null>(null);
  const [playKey, setPlayKey] = useState(0);

  useEffect(() => {
    api.listAlgorithms().then((list) => {
      setAlgos(list);
      setSelected(list[0] ?? "");
    });
  }, []);

  useEffect(() => {
    if (!selected) return;
    api.getAlgorithm(selected).then((s) => {
      setSpec(s);
      setScenario(s.scenarios[0] ?? null);
      setPlayKey((k) => k + 1);
    });
  }, [selected]);

  return (
    <div style={{ display: "grid", gridTemplateColumns: "220px 1fr", gap: 16 }}>
      <aside className="panel">
        <h2>Algorithm</h2>
        <div className="scenario-list">
          {algos.map((a) => (
            <button
              key={a}
              className={a === selected ? "active" : ""}
              onClick={() => setSelected(a)}
            >
              {a}
            </button>
          ))}
        </div>
      </aside>

      <div>
        {spec && (
          <>
            <div className="panel">
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
                <div>
                  <h2 style={{ marginBottom: 4 }}>
                    {spec.name}{" "}
                    <span className={`badge ${spec.family.toLowerCase()}`}>{spec.family}</span>
                  </h2>
                  <p style={{ color: "var(--muted)", maxWidth: 720, lineHeight: 1.5 }}>
                    {spec.summary}
                  </p>
                </div>
              </div>
            </div>

            <div className="grid-2">
              <div className="panel">
                <h2>Scenario</h2>
                <div className="scenario-list">
                  {spec.scenarios.map((s) => (
                    <button
                      key={s.id}
                      className={scenario?.id === s.id ? "active" : ""}
                      onClick={() => {
                        setScenario(s);
                        setPlayKey((k) => k + 1);
                      }}
                    >
                      <div style={{ fontWeight: 600 }}>{s.title}</div>
                      <div style={{ color: "var(--muted)", fontSize: 12, marginTop: 4 }}>
                        {s.description}
                      </div>
                    </button>
                  ))}
                </div>
                {scenario && (
                  <button
                    className="primary"
                    style={{ marginTop: 12 }}
                    onClick={() => setPlayKey((k) => k + 1)}
                  >
                    ▶ Replay
                  </button>
                )}
              </div>

              <div className="panel" style={{ minHeight: 600 }}>
                <h2>Network</h2>
                {spec && scenario && (
                  <NetworkCanvas
                    key={playKey}
                    nodes={spec.nodes}
                    edges={spec.edges}
                    steps={scenario.steps}
                  />
                )}
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
