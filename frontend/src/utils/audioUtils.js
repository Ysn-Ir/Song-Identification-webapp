/**
 * audioUtils.js - Studio Grade DSP & Web Audio Toolkit
 * 
 * Provides:
 * - 16-bit PCM uncompressed WAV encoder for native C++ libsndfile compatibility.
 * - Microphone recorder using Web Audio API ScriptProcessorNode.
 * - Hardware-style 64-band FFT Spectrum Analyzer with peak-hold decay.
 * - Real-time Oscilloscope Waveform visualizer with phosphor cyan trace.
 * - RMS & Peak dB VU meter calculations (-60 dB to 0 dB).
 */

/**
 * Encodes Float32Array PCM samples into standard 16-bit PCM RIFF WAV format.
 */
export function encodeWAV(samples, sampleRate = 44100) {
  const buffer = new ArrayBuffer(44 + samples.length * 2);
  const view = new DataView(buffer);

  /* RIFF chunk descriptor */
  writeAscii(view, 0, 'RIFF');
  view.setUint32(4, 36 + samples.length * 2, true);
  writeAscii(view, 8, 'WAVE');

  /* fmt sub-chunk */
  writeAscii(view, 12, 'fmt ');
  view.setUint32(16, 16, true);             // Subchunk1Size (16 for PCM)
  view.setUint16(20, 1, true);              // AudioFormat (1 = PCM uncompressed)
  view.setUint16(22, 1, true);              // NumChannels (1 = Mono)
  view.setUint32(24, sampleRate, true);     // SampleRate
  view.setUint32(28, sampleRate * 2, true); // ByteRate (SampleRate * NumChannels * BitsPerSample/8)
  view.setUint16(32, 2, true);              // BlockAlign (NumChannels * BitsPerSample/8)
  view.setUint16(34, 16, true);             // BitsPerSample (16 bits)

  /* data sub-chunk */
  writeAscii(view, 36, 'data');
  view.setUint32(40, samples.length * 2, true);

  // Write 16-bit signed PCM samples
  let offset = 44;
  for (let i = 0; i < samples.length; i++, offset += 2) {
    const s = Math.max(-1, Math.min(1, samples[i]));
    view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7FFF, true);
  }

  return buffer;
}

function writeAscii(view, offset, str) {
  for (let i = 0; i < str.length; i++) {
    view.setUint8(offset + i, str.charCodeAt(i));
  }
}

/**
 * Microphone Recorder with Web Audio API.
 * Guarantees uncompressed WAV output.
 */
export class WavAudioRecorder {
  constructor() {
    this.audioContext = null;
    this.stream = null;
    this.source = null;
    this.processor = null;
    this.analyser = null;
    this.recordedBuffers = [];
    this.isRecording = false;
  }

  async start() {
    this.stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        echoCancellation: false,
        noiseSuppression: false,
        autoGainControl: false,
      },
    });

    const AudioContextClass = window.AudioContext || window.webkitAudioContext;
    this.audioContext = new AudioContextClass();

    this.source = this.audioContext.createMediaStreamSource(this.stream);

    // Fast Fourier Transform analyser
    this.analyser = this.audioContext.createAnalyser();
    this.analyser.fftSize = 512;
    this.analyser.smoothingTimeConstant = 0.75;
    this.source.connect(this.analyser);

    // ScriptProcessor for PCM extraction
    this.processor = this.audioContext.createScriptProcessor(4096, 1, 1);
    this.recordedBuffers = [];

    this.processor.onaudioprocess = (e) => {
      if (!this.isRecording) return;
      const input = e.inputBuffer.getChannelData(0);
      this.recordedBuffers.push(new Float32Array(input));
    };

    this.source.connect(this.processor);
    this.processor.connect(this.audioContext.destination);

    this.isRecording = true;
  }

  getAnalyser() {
    return this.analyser;
  }

  stop() {
    this.isRecording = false;

    if (this.processor) {
      this.processor.disconnect();
      this.processor.onaudioprocess = null;
    }
    if (this.source) {
      this.source.disconnect();
    }
    if (this.stream) {
      this.stream.getTracks().forEach((t) => t.stop());
    }

    const sampleRate = this.audioContext ? this.audioContext.sampleRate : 44100;
    if (this.audioContext) {
      this.audioContext.close();
    }

    let totalLength = 0;
    for (const buf of this.recordedBuffers) {
      totalLength += buf.length;
    }

    const merged = new Float32Array(totalLength);
    let offset = 0;
    for (const buf of this.recordedBuffers) {
      merged.set(buf, offset);
      offset += buf.length;
    }

    // Studio-grade peak normalization: boost quiet microphone captures to reference full scale
    let maxAmp = 0;
    for (let i = 0; i < merged.length; i++) {
      const abs = Math.abs(merged[i]);
      if (abs > maxAmp) maxAmp = abs;
    }

    if (maxAmp > 0.001) {
      const gain = 0.95 / maxAmp;
      for (let i = 0; i < merged.length; i++) {
        merged[i] = merged[i] * gain;
      }
    }

    const wavBuffer = encodeWAV(merged, sampleRate);
    const blob = new Blob([wavBuffer], { type: 'audio/wav' });
    return new File([blob], `mic_capture_${Date.now()}.wav`, { type: 'audio/wav' });
  }
}

/**
 * Renders a studio-grade dual visualizer (FFT Frequency Spectrum with falling peak caps
 * plus Oscilloscope waveform trace).
 */
export function renderStudioVisualizer(canvas, analyser, mode = 'spectrum') {
  if (!canvas || !analyser) return () => {};

  const ctx = canvas.getContext('2d');
  const bufferLength = analyser.frequencyBinCount;
  const freqData = new Uint8Array(bufferLength);
  const timeData = new Uint8Array(bufferLength);

  // Peak caps array for spectrum mode
  const bandCount = 48;
  const peakCaps = new Array(bandCount).fill(0);
  const peakDecay = 0.85;

  let animationId;

  const render = () => {
    animationId = requestAnimationFrame(render);

    const width = canvas.width;
    const height = canvas.height;

    // Subtle clear with slight fade trail for analog phosphor monitor look
    ctx.fillStyle = 'rgba(10, 14, 23, 0.35)';
    ctx.fillRect(0, 0, width, height);

    // Subtle horizontal gridlines (-12dB, -24dB, -36dB)
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.05)';
    ctx.lineWidth = 1;
    for (let y = height * 0.25; y < height; y += height * 0.25) {
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(width, y);
      ctx.stroke();
    }

    if (mode === 'spectrum') {
      analyser.getByteFrequencyData(freqData);

      const spacing = 3;
      const barWidth = (width - spacing * (bandCount - 1)) / bandCount;

      for (let i = 0; i < bandCount; i++) {
        // Logarithmic frequency bin mapping (concentrating on bass and human voice range 80Hz - 4kHz)
        const logIndex = Math.floor(Math.pow(i / bandCount, 1.6) * (bufferLength * 0.75));
        const val = freqData[logIndex] || 0;
        const normalized = val / 255;
        const barHeight = Math.max(3, normalized * (height - 8));

        const x = i * (barWidth + spacing);
        const y = height - barHeight;

        // Peak cap physics
        if (barHeight > peakCaps[i]) {
          peakCaps[i] = barHeight;
        } else {
          peakCaps[i] = Math.max(0, peakCaps[i] - peakDecay);
        }

        // Electric cyan to neon indigo gradient
        const grad = ctx.createLinearGradient(0, y, 0, height);
        grad.addColorStop(0, '#00f0ff');
        grad.addColorStop(0.5, '#0088ff');
        grad.addColorStop(1, '#3b82f6');

        ctx.fillStyle = grad;
        ctx.fillRect(x, y, barWidth, barHeight);

        // Peak hold cap (amber/white accent)
        if (peakCaps[i] > 4) {
          ctx.fillStyle = peakCaps[i] > height * 0.85 ? '#ef4444' : '#00f0ff';
          ctx.fillRect(x, height - peakCaps[i] - 2, barWidth, 2);
        }
      }
    } else {
      // Oscilloscope trace mode
      analyser.getByteTimeDomainData(timeData);

      ctx.lineWidth = 2;
      ctx.strokeStyle = '#00f0ff';
      ctx.shadowColor = 'rgba(0, 240, 255, 0.75)';
      ctx.shadowBlur = 8;

      ctx.beginPath();
      const sliceWidth = width / bufferLength;
      let x = 0;

      for (let i = 0; i < bufferLength; i++) {
        const v = timeData[i] / 128.0;
        const y = (v * height) / 2;

        if (i === 0) {
          ctx.moveTo(x, y);
        } else {
          ctx.lineTo(x, y);
        }
        x += sliceWidth;
      }

      ctx.stroke();
      ctx.shadowBlur = 0;
    }
  };

  render();

  return () => {
    cancelAnimationFrame(animationId);
  };
}

/**
 * Calculates current audio decibel level and RMS from an active AnalyserNode.
 */
export function getDecibelLevel(analyser) {
  if (!analyser) return { rms: 0, db: -60 };
  const data = new Uint8Array(analyser.frequencyBinCount);
  analyser.getByteTimeDomainData(data);

  let sumSquares = 0;
  for (let i = 0; i < data.length; i++) {
    const norm = (data[i] - 128) / 128;
    sumSquares += norm * norm;
  }
  const rms = Math.sqrt(sumSquares / data.length);
  const db = rms > 0.0001 ? Math.max(-60, Math.min(0, 20 * Math.log10(rms))) : -60;
  return { rms, db: Math.round(db) };
}

export function formatBytes(bytes, decimals = 1) {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const dm = decimals < 0 ? 0 : decimals;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i];
}
