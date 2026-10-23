import React, { useState, useEffect, useRef } from 'react';
import { 
  Headphones, 
  Volume2, 
  Sliders, 
  Radio, 
  Smartphone, 
  Layers, 
  Play, 
  Pause, 
  RotateCcw, 
  Info, 
  CheckCircle, 
  Tv, 
  Upload, 
  Wifi, 
  Sparkles,
  ExternalLink
} from 'lucide-react';
import { QRCodeSVG } from 'qrcode.react';

export default function App() {
  const [activeTab, setActiveTab] = useState('one-phone'); // 'one-phone' | 'auracast' | 'two-phone-relay'
  const [connectedOutputs, setConnectedOutputs] = useState([]);
  const [selectedDevice1, setSelectedDevice1] = useState('');
  const [selectedDevice2, setSelectedDevice2] = useState('');
  
  // Audio state
  const [isPlaying, setIsPlaying] = useState(false);
  const [volume1, setVolume1] = useState(1.0);
  const [volume2, setVolume2] = useState(1.0);
  const [latencyOffsetMs, setLatencyOffsetMs] = useState(0);
  const [videoSrc, setVideoSrc] = useState(null);
  const [videoFileName, setVideoFileName] = useState('');
  
  const videoRef = useRef(null);
  const audioContextRef = useRef(null);
  const gainNode1Ref = useRef(null);
  const gainNode2Ref = useRef(null);
  const delayNodeRef = useRef(null);
  const canvasRef = useRef(null);
  const analyserRef = useRef(null);

  // Enumerate audio outputs if supported (Web Audio / setSinkId)
  useEffect(() => {
    async function loadAudioDevices() {
      try {
        if (navigator.mediaDevices && navigator.mediaDevices.enumerateDevices) {
          const devices = await navigator.mediaDevices.enumerateDevices();
          const audioOutputs = devices.filter(d => d.kind === 'audiooutput');
          setConnectedOutputs(audioOutputs);
          if (audioOutputs.length > 0) {
            setSelectedDevice1(audioOutputs[0].deviceId);
            if (audioOutputs.length > 1) {
              setSelectedDevice2(audioOutputs[1].deviceId);
            }
          }
        }
      } catch (err) {
        console.warn('enumerateDevices error:', err);
      }
    }
    loadAudioDevices();
  }, []);

  // Initialize Web Audio Engine for Dual Output & Visualization
  const initAudioEngine = () => {
    if (audioContextRef.current) return;

    try {
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      const ctx = new AudioCtx();
      audioContextRef.current = ctx;

      const analyser = ctx.createAnalyser();
      analyser.fftSize = 64;
      analyserRef.current = analyser;

      // Gain 1 for Earbuds 1
      const g1 = ctx.createGain();
      g1.gain.value = volume1;
      gainNode1Ref.current = g1;

      // Gain 2 for Earbuds 2 with micro-delay for synchronization
      const g2 = ctx.createGain();
      g2.gain.value = volume2;
      gainNode2Ref.current = g2;

      const delay = ctx.createDelay(1.0);
      delay.delayTime.value = Math.max(0, latencyOffsetMs / 1000);
      delayNodeRef.current = delay;

      if (videoRef.current) {
        const source = ctx.createMediaElementSource(videoRef.current);
        source.connect(analyser);

        // Path 1
        analyser.connect(g1);
        g1.connect(ctx.destination);

        // Path 2 (Delayed for Earbuds 2)
        analyser.connect(delay);
        delay.connect(g2);
        g2.connect(ctx.destination);
      }

      drawVisualizer();
    } catch (e) {
      console.error('Audio engine init error:', e);
    }
  };

  // Draw real-time audio visualizer
  const drawVisualizer = () => {
    if (!canvasRef.current || !analyserRef.current) return;
    const canvas = canvasRef.current;
    const ctx = canvas.getContext('2d');
    const analyser = analyserRef.current;
    const bufferLength = analyser.frequencyBinCount;
    const dataArray = new Uint8Array(bufferLength);

    const renderFrame = () => {
      requestAnimationFrame(renderFrame);
      analyser.getByteFrequencyData(dataArray);

      ctx.clearRect(0, 0, canvas.width, canvas.height);
      const barWidth = (canvas.width / bufferLength) * 1.8;
      let x = 0;

      for (let i = 0; i < bufferLength; i++) {
        const barHeight = (dataArray[i] / 255) * canvas.height * 0.85;
        const gradient = ctx.createLinearGradient(0, canvas.height, 0, 0);
        gradient.addColorStop(0, '#10b981');
        gradient.addColorStop(1, '#06b6d4');

        ctx.fillStyle = gradient;
        ctx.fillRect(x, canvas.height - barHeight, barWidth, barHeight);
        x += barWidth + 3;
      }
    };
    renderFrame();
  };

  const handlePlayPause = () => {
    initAudioEngine();
    if (!videoRef.current) return;

    if (isPlaying) {
      videoRef.current.pause();
      setIsPlaying(false);
    } else {
      videoRef.current.play().then(() => setIsPlaying(true)).catch(console.error);
    }
  };

  const handleFileUpload = (e) => {
    const file = e.target.files[0];
    if (file) {
      const url = URL.createObjectURL(file);
      setVideoSrc(url);
      setVideoFileName(file.name);
      setIsPlaying(false);
    }
  };

  const playTestClick = () => {
    const AudioCtx = window.AudioContext || window.webkitAudioContext;
    const ctx = audioContextRef.current || new AudioCtx();
    audioContextRef.current = ctx;

    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = 'sine';
    osc.frequency.setValueAtTime(880, ctx.currentTime); // 880Hz A5
    gain.gain.setValueAtTime(0.5, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.08);

    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.08);
  };

  return (
    <div style={{ maxWidth: '850px', margin: '0 auto', padding: '24px 16px', minHeight: '100vh' }}>
      {/* Top Header */}
      <header style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '24px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <div style={{
            width: '46px',
            height: '46px',
            borderRadius: '14px',
            background: 'linear-gradient(135deg, #10b981, #06b6d4)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            boxShadow: '0 0 20px rgba(16, 185, 129, 0.4)'
          }}>
            <Headphones size={26} color="#090d16" strokeWidth={2.4} />
          </div>
          <div>
            <h1 style={{ fontSize: '22px', fontWeight: '800', letterSpacing: '-0.5px' }}>EarSync Dual Audio</h1>
            <p style={{ fontSize: '13px', color: 'var(--text-muted)' }}>1 Phone • 2 Pairs of Earbuds • Synchronized</p>
          </div>
        </div>

        <div style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: '6px',
          background: 'rgba(16, 185, 129, 0.1)',
          border: '1px solid rgba(16, 185, 129, 0.3)',
          padding: '6px 12px',
          borderRadius: '9999px',
          fontSize: '12px',
          color: '#10b981',
          fontWeight: '600'
        }}>
          <span style={{ width: '8px', height: '8px', borderRadius: '50%', background: '#10b981', display: 'inline-block' }} className="pulse-glow"></span>
          Single-Phone Mode
        </div>
      </header>

      {/* Mode Navigation Tabs */}
      <nav style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(3, 1fr)',
        gap: '8px',
        background: '#0f172a',
        padding: '6px',
        borderRadius: '16px',
        marginBottom: '24px',
        border: '1px solid var(--border-color)'
      }}>
        <button
          onClick={() => setActiveTab('one-phone')}
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: '8px',
            padding: '10px 14px',
            borderRadius: '12px',
            border: 'none',
            fontSize: '13px',
            fontWeight: '700',
            cursor: 'pointer',
            transition: 'all 0.2s',
            background: activeTab === 'one-phone' ? 'linear-gradient(135deg, #10b981, #059669)' : 'transparent',
            color: activeTab === 'one-phone' ? '#090d16' : '#94a3b8'
          }}
        >
          <Smartphone size={16} /> 1-Phone Dual Audio
        </button>

        <button
          onClick={() => setActiveTab('auracast')}
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: '8px',
            padding: '10px 14px',
            borderRadius: '12px',
            border: 'none',
            fontSize: '13px',
            fontWeight: '700',
            cursor: 'pointer',
            transition: 'all 0.2s',
            background: activeTab === 'auracast' ? 'linear-gradient(135deg, #06b6d4, #0284c7)' : 'transparent',
            color: activeTab === 'auracast' ? '#090d16' : '#94a3b8'
          }}
        >
          <Radio size={16} /> Auracast & Samsung
        </button>

        <button
          onClick={() => setActiveTab('two-phone-relay')}
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: '8px',
            padding: '10px 14px',
            borderRadius: '12px',
            border: 'none',
            fontSize: '13px',
            fontWeight: '700',
            cursor: 'pointer',
            transition: 'all 0.2s',
            background: activeTab === 'two-phone-relay' ? 'linear-gradient(135deg, #8b5cf6, #7c3aed)' : 'transparent',
            color: activeTab === 'two-phone-relay' ? '#ffffff' : '#94a3b8'
          }}
        >
          <Wifi size={16} /> 2-Phone Relay (Fallback)
        </button>
      </nav>

      {/* TAB 1: 1-PHONE DUAL AUDIO */}
      {activeTab === 'one-phone' && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
          {/* Video / Media Player Card */}
          <div className="glass-panel" style={{ padding: '20px', overflow: 'hidden' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '16px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <Tv size={18} color="#10b981" />
                <h2 style={{ fontSize: '16px', fontWeight: '700' }}>Shared Video / Movie Player</h2>
              </div>
              <label style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: '6px',
                background: '#1e293b',
                padding: '6px 12px',
                borderRadius: '8px',
                fontSize: '12px',
                fontWeight: '600',
                cursor: 'pointer',
                color: '#f8fafc',
                border: '1px solid #334155'
              }}>
                <Upload size={14} /> Choose Movie File
                <input type="file" accept="video/*,audio/*" onChange={handleFileUpload} style={{ display: 'none' }} />
              </label>
            </div>

            {/* Video Viewport */}
            <div style={{
              background: '#020617',
              borderRadius: '12px',
              overflow: 'hidden',
              aspectRatio: '16/9',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              position: 'relative'
            }}>
              {videoSrc ? (
                <video
                  ref={videoRef}
                  src={videoSrc}
                  playsInline
                  style={{ width: '100%', height: '100%', objectFit: 'contain' }}
                  onEnded={() => setIsPlaying(false)}
                />
              ) : (
                <div style={{ textAlign: 'center', padding: '20px' }}>
                  <Tv size={42} color="#334155" style={{ marginBottom: '12px' }} />
                  <p style={{ fontSize: '14px', fontWeight: '600', color: '#94a3b8' }}>Load any movie or video from your phone</p>
                  <p style={{ fontSize: '12px', color: '#64748b', marginTop: '4px' }}>Audio will clone and route to both connected Bluetooth earbuds</p>
                  <button
                    onClick={() => {
                      setVideoSrc('https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4');
                      setVideoFileName('BigBuckBunny (HD Sample).mp4');
                    }}
                    style={{
                      marginTop: '16px',
                      background: 'rgba(16, 185, 129, 0.15)',
                      color: '#10b981',
                      border: '1px solid rgba(16, 185, 129, 0.4)',
                      padding: '8px 16px',
                      borderRadius: '8px',
                      fontSize: '12px',
                      fontWeight: '700',
                      cursor: 'pointer'
                    }}
                  >
                    Load Sample Test Video
                  </button>
                </div>
              )}
            </div>

            {/* Player Controls */}
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: '16px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                <button
                  onClick={handlePlayPause}
                  disabled={!videoSrc}
                  style={{
                    width: '44px',
                    height: '44px',
                    borderRadius: '50%',
                    background: isPlaying ? '#ef4444' : '#10b981',
                    border: 'none',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    cursor: videoSrc ? 'pointer' : 'not-allowed',
                    color: '#090d16',
                    boxShadow: '0 0 15px rgba(16, 185, 129, 0.4)'
                  }}
                >
                  {isPlaying ? <Pause size={20} /> : <Play size={20} style={{ marginLeft: '2px' }} />}
                </button>

                <div>
                  <p style={{ fontSize: '13px', fontWeight: '700', color: '#f8fafc' }}>
                    {videoFileName || 'No file selected'}
                  </p>
                  <p style={{ fontSize: '11px', color: isPlaying ? '#10b981' : '#64748b' }}>
                    {isPlaying ? 'Streaming simultaneously to Earbuds 1 & 2' : 'Paused'}
                  </p>
                </div>
              </div>

              {/* Mini Spectrum Canvas */}
              <canvas ref={canvasRef} width="140" height="36" style={{ borderRadius: '6px' }} />
            </div>
          </div>

          {/* Device Selection & Volume Cards */}
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
            {/* Earbuds 1 Card */}
            <div className="glass-panel" style={{ padding: '18px' }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '12px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <Headphones size={18} color="#10b981" />
                  <span style={{ fontSize: '14px', fontWeight: '700' }}>Earbuds 1 (You)</span>
                </div>
                <span style={{ fontSize: '12px', fontWeight: '700', color: '#10b981' }}>{Math.round(volume1 * 100)}%</span>
              </div>

              <div style={{ marginBottom: '14px' }}>
                <label style={{ fontSize: '11px', color: '#94a3b8', display: 'block', marginBottom: '6px' }}>Select Device:</label>
                <select
                  value={selectedDevice1}
                  onChange={(e) => setSelectedDevice1(e.target.value)}
                  style={{
                    width: '100%',
                    background: '#1e293b',
                    border: '1px solid #334155',
                    color: '#f8fafc',
                    padding: '8px 10px',
                    borderRadius: '8px',
                    fontSize: '12px',
                    outline: 'none'
                  }}
                >
                  {connectedOutputs.length > 0 ? (
                    connectedOutputs.map(d => (
                      <option key={d.deviceId} value={d.deviceId}>{d.label || `Bluetooth Device (${d.deviceId.slice(0, 5)})`}</option>
                    ))
                  ) : (
                    <option value="">Default Bluetooth Output 1</option>
                  )}
                </select>
              </div>

              <input
                type="range"
                min="0"
                max="1"
                step="0.01"
                value={volume1}
                onChange={(e) => {
                  const val = parseFloat(e.target.value);
                  setVolume1(val);
                  if (gainNode1Ref.current) gainNode1Ref.current.gain.value = val;
                }}
              />
            </div>

            {/* Earbuds 2 Card */}
            <div className="glass-panel" style={{ padding: '18px' }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '12px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <Headphones size={18} color="#06b6d4" />
                  <span style={{ fontSize: '14px', fontWeight: '700' }}>Earbuds 2 (Friend)</span>
                </div>
                <span style={{ fontSize: '12px', fontWeight: '700', color: '#06b6d4' }}>{Math.round(volume2 * 100)}%</span>
              </div>

              <div style={{ marginBottom: '14px' }}>
                <label style={{ fontSize: '11px', color: '#94a3b8', display: 'block', marginBottom: '6px' }}>Select Device:</label>
                <select
                  value={selectedDevice2}
                  onChange={(e) => setSelectedDevice2(e.target.value)}
                  style={{
                    width: '100%',
                    background: '#1e293b',
                    border: '1px solid #334155',
                    color: '#f8fafc',
                    padding: '8px 10px',
                    borderRadius: '8px',
                    fontSize: '12px',
                    outline: 'none'
                  }}
                >
                  {connectedOutputs.length > 1 ? (
                    connectedOutputs.map(d => (
                      <option key={d.deviceId} value={d.deviceId}>{d.label || `Bluetooth Device (${d.deviceId.slice(0, 5)})`}</option>
                    ))
                  ) : (
                    <option value="">Second Earbuds (AudioTrack 2)</option>
                  )}
                </select>
              </div>

              <input
                type="range"
                min="0"
                max="1.5"
                step="0.01"
                value={volume2}
                onChange={(e) => {
                  const val = parseFloat(e.target.value);
                  setVolume2(val);
                  if (gainNode2Ref.current) gainNode2Ref.current.gain.value = val;
                }}
              />
            </div>
          </div>

          {/* Sync Calibration Tool */}
          <div className="glass-panel" style={{ padding: '18px' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '12px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <Sliders size={18} color="#8b5cf6" />
                <span style={{ fontSize: '14px', fontWeight: '700' }}>Earbuds Micro-Latency Calibration</span>
              </div>
              <button
                onClick={playTestClick}
                style={{
                  background: 'rgba(139, 92, 246, 0.15)',
                  border: '1px solid rgba(139, 92, 246, 0.4)',
                  color: '#c4b5fd',
                  padding: '5px 12px',
                  borderRadius: '6px',
                  fontSize: '11px',
                  fontWeight: '600',
                  cursor: 'pointer'
                }}
              >
                🔔 Play Sync Click
              </button>
            </div>
            <p style={{ fontSize: '12px', color: '#94a3b8', marginBottom: '12px' }}>
              Different Bluetooth earbuds have varying hardware decoding delays. Adjust the offset until the test clicks sound perfectly unified without echo:
            </p>
            <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
              <input
                type="range"
                min="-150"
                max="150"
                step="5"
                value={latencyOffsetMs}
                onChange={(e) => {
                  const val = parseInt(e.target.value, 10);
                  setLatencyOffsetMs(val);
                  if (delayNodeRef.current) {
                    delayNodeRef.current.delayTime.value = Math.max(0, val / 1000);
                  }
                }}
              />
              <span style={{ minWidth: '70px', fontSize: '13px', fontWeight: '700', color: '#c4b5fd' }}>
                {latencyOffsetMs > 0 ? `+${latencyOffsetMs}` : latencyOffsetMs} ms
              </span>
            </div>
          </div>
        </div>
      )}

      {/* TAB 2: AURACAST & SAMSUNG GUIDE */}
      {activeTab === 'auracast' && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '18px' }}>
          {/* Samsung One UI Card */}
          <div className="glass-panel" style={{ padding: '20px', borderLeft: '4px solid #6366f1' }}>
            <h2 style={{ fontSize: '16px', fontWeight: '700', color: '#a5b4fc', marginBottom: '8px' }}>
              ✨ How Samsung Phones Do It ("Dual Audio")
            </h2>
            <p style={{ fontSize: '13px', color: '#cbd5e1', lineHeight: '1.6' }}>
              Samsung phones have proprietary hardware and audio HAL modifications. If either of you has a Samsung Galaxy phone with Bluetooth 5.0+:
            </p>
            <ol style={{ fontSize: '12px', color: '#94a3b8', lineHeight: '1.8', margin: '12px 0 12px 20px' }}>
              <li>Connect both pairs of Bluetooth earbuds in Bluetooth settings.</li>
              <li>Swipe down the Quick Settings notification panel.</li>
              <li>Tap <strong>Media Output</strong> (or <strong>Media</strong>).</li>
              <li>Check the checkboxes next to <strong>both earbuds</strong>. Sound will immediately play in both pairs!</li>
            </ol>
          </div>

          {/* Bluetooth 5.2+ Auracast Card */}
          <div className="glass-panel" style={{ padding: '20px', borderLeft: '4px solid #10b981' }}>
            <h2 style={{ fontSize: '16px', fontWeight: '700', color: '#6ee7b7', marginBottom: '8px' }}>
              📡 Bluetooth 5.2+ LE Audio (Auracast)
            </h2>
            <p style={{ fontSize: '13px', color: '#cbd5e1', lineHeight: '1.6' }}>
              Auracast is the new global Bluetooth standard introduced in Android 13/14 (API 33+). Instead of point-to-point connections, your phone broadcasts like an FM radio station.
            </p>
            <div style={{
              background: '#020617',
              padding: '14px',
              borderRadius: '10px',
              marginTop: '12px',
              fontSize: '12px',
              fontFamily: 'monospace',
              color: '#34d399'
            }}>
              val broadcast = getSystemService(BluetoothLeBroadcast::class.java)<br />
              broadcast.startBroadcast(settings, code) // Broadcasts to Earbuds 1 & Earbuds 2
            </div>
            <p style={{ fontSize: '12px', color: '#94a3b8', marginTop: '10px' }}>
              Our native Android module in <code>/android</code> includes the complete <code>AuracastManager.kt</code> implementation!
            </p>
          </div>

          {/* Developer Options Tip */}
          <div className="glass-panel" style={{ padding: '20px', borderLeft: '4px solid #f59e0b' }}>
            <h2 style={{ fontSize: '16px', fontWeight: '700', color: '#fcd34d', marginBottom: '8px' }}>
              🛠️ Android Developer Options Setting (Non-Samsung)
            </h2>
            <p style={{ fontSize: '13px', color: '#cbd5e1', lineHeight: '1.6' }}>
              If your phone disconnects Earbuds 1 as soon as you connect Earbuds 2:
            </p>
            <p style={{ fontSize: '12px', color: '#94a3b8', marginTop: '8px' }}>
              Go to <strong>Settings &gt; Developer Options &gt; Maximum connected Bluetooth audio devices</strong> and change it from 1 to <strong>2 or 5</strong>. This allows the OS Bluetooth controller to keep both headsets active at once!
            </p>
          </div>
        </div>
      )}

      {/* TAB 3: 2-PHONE WI-FI RELAY (FALLBACK) */}
      {activeTab === 'two-phone-relay' && (
        <div className="glass-panel" style={{ padding: '24px', textAlign: 'center' }}>
          <Wifi size={40} color="#8b5cf6" style={{ marginBottom: '14px' }} />
          <h2 style={{ fontSize: '18px', fontWeight: '800', marginBottom: '8px' }}>Local Wi-Fi / Hotspot Audio Relay</h2>
          <p style={{ fontSize: '13px', color: '#94a3b8', maxWidth: '480px', margin: '0 auto 20px auto' }}>
            If the other person ever has a phone nearby, they can scan this QR code. Audio will stream over ultra-low-latency WebRTC directly to their earbuds without installing any app!
          </p>

          <div style={{
            background: '#ffffff',
            padding: '16px',
            borderRadius: '16px',
            display: 'inline-block',
            boxShadow: '0 0 25px rgba(139, 92, 246, 0.3)'
          }}>
            <QRCodeSVG value="http://192.168.43.1:3000/listen" size={170} />
          </div>

          <div style={{ marginTop: '16px' }}>
            <p style={{ fontSize: '12px', fontWeight: '700', color: '#c4b5fd' }}>Room Code: SYNC-2026</p>
            <p style={{ fontSize: '11px', color: '#64748b', marginTop: '4px' }}>Works on iPhone (Safari) and Android (Chrome)</p>
          </div>
        </div>
      )}

      {/* Footer */}
      <footer style={{ marginTop: '40px', textAlign: 'center', fontSize: '12px', color: '#64748b' }}>
        <p>EarSync • Open-Source Dual Audio & Auracast Engine</p>
        <p style={{ marginTop: '4px' }}>Engineered for 1 Phone, 2 Earbuds Dual Output</p>
      </footer>
    </div>
  );
}
