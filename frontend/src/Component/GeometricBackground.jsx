import { useEffect, useRef } from "react";
import "./GeometricBackground.css";

export default function GeometricBackground() {
  const canvasRef = useRef(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    let animationFrameId;
    let width = (canvas.width = window.innerWidth);
    let height = (canvas.height = window.innerHeight);

    // Mouse coordinates for interactive parallax & deflection
    const mouse = {
      x: width / 2,
      y: height / 2,
      targetX: width / 2,
      targetY: height / 2,
      isHovered: false,
    };

    const handleResize = () => {
      width = canvas.width = window.innerWidth;
      height = canvas.height = window.innerHeight;
      initNodes();
    };

    const handleMouseMove = (e) => {
      mouse.targetX = e.clientX;
      mouse.targetY = e.clientY;
      mouse.isHovered = true;
    };

    const handleMouseLeave = () => {
      mouse.targetX = width / 2;
      mouse.targetY = height / 2;
      mouse.isHovered = false;
    };

    window.addEventListener("resize", handleResize);
    window.addEventListener("mousemove", handleMouseMove);
    document.addEventListener("mouseleave", handleMouseLeave);

    // Node count scaled gracefully to screen width
    const NODE_COUNT = Math.min(68, Math.max(38, Math.floor(width / 32)));
    let nodes = [];

    function initNodes() {
      nodes = [];
      for (let i = 0; i < NODE_COUNT; i++) {
        nodes.push({
          x: Math.random() * width,
          y: Math.random() * height,
          originX: Math.random() * width,
          originY: Math.random() * height,
          vx: (Math.random() - 0.5) * 0.45,
          vy: (Math.random() - 0.5) * 0.45,
          radius: Math.random() * 2 + 1.2,
          phase: Math.random() * Math.PI * 2,
          speed: Math.random() * 0.02 + 0.008,
          depth: Math.random() * 0.6 + 0.4, // 3D depth layer
        });
      }
    }

    initNodes();

    let time = 0;

    const render = () => {
      time += 0.012;

      // Smooth mouse easing
      mouse.x += (mouse.targetX - mouse.x) * 0.04;
      mouse.y += (mouse.targetY - mouse.y) * 0.04;

      // Base Canvas Clear & Abyssal Midnight Gradient
      ctx.clearRect(0, 0, width, height);

      // 1. Ambient Luminous Cosmic Gradients
      const ambientGlow = ctx.createRadialGradient(
        mouse.x,
        mouse.y * 0.8,
        50,
        width * 0.5,
        height * 0.45,
        Math.max(width, height) * 0.85
      );
      ambientGlow.addColorStop(0, "rgba(8, 28, 56, 0.45)");
      ambientGlow.addColorStop(0.35, "rgba(4, 18, 38, 0.35)");
      ambientGlow.addColorStop(0.7, "rgba(2, 8, 20, 0.6)");
      ambientGlow.addColorStop(1, "rgba(1, 4, 10, 0.95)");

      ctx.fillStyle = ambientGlow;
      ctx.fillRect(0, 0, width, height);

      // 2. Undulating Sacred Acoustic Topographic Ribbons (Geometric Waves)
      const WAVE_COUNT = 4;
      for (let w = 0; w < WAVE_COUNT; w++) {
        ctx.beginPath();
        const baseOffset = height * (0.35 + w * 0.14);
        const freq = 0.0018 + w * 0.0006;
        const amp = 32 + w * 14;
        const waveSpeed = time * (0.5 + w * 0.25);

        ctx.moveTo(0, height);
        ctx.lineTo(0, baseOffset);

        for (let x = 0; x <= width; x += 18) {
          // Compound sinusoidal waves simulating harmonics
          const sine1 = Math.sin(x * freq + waveSpeed);
          const sine2 = Math.cos(x * freq * 1.6 - waveSpeed * 0.7);
          const mouseDist = Math.abs(x - mouse.x);
          const mouseInfluence = Math.max(0, 1 - mouseDist / 400) * 18 * Math.sin(time * 2);

          const y = baseOffset + (sine1 * 0.7 + sine2 * 0.3) * amp + mouseInfluence;
          ctx.lineTo(x, y);
        }

        ctx.lineTo(width, height);
        ctx.closePath();

        // Subtle crystalline ribbon gradient
        const waveGradient = ctx.createLinearGradient(0, baseOffset - amp, width, baseOffset + amp);
        if (w === 0) {
          waveGradient.addColorStop(0, "rgba(0, 210, 255, 0.03)");
          waveGradient.addColorStop(0.5, "rgba(6, 214, 160, 0.04)");
          waveGradient.addColorStop(1, "rgba(2, 132, 199, 0.02)");
        } else if (w === 1) {
          waveGradient.addColorStop(0, "rgba(99, 102, 241, 0.025)");
          waveGradient.addColorStop(0.5, "rgba(0, 210, 255, 0.035)");
          waveGradient.addColorStop(1, "rgba(14, 165, 233, 0.02)");
        } else {
          waveGradient.addColorStop(0, "rgba(2, 132, 199, 0.02)");
          waveGradient.addColorStop(0.7, "rgba(0, 210, 255, 0.03)");
          waveGradient.addColorStop(1, "rgba(6, 214, 160, 0.015)");
        }

        ctx.fillStyle = waveGradient;
        ctx.fill();

        // Wave outline filament
        ctx.lineWidth = 1;
        ctx.strokeStyle =
          w === 0
            ? "rgba(56, 189, 248, 0.16)"
            : w === 1
            ? "rgba(6, 214, 160, 0.12)"
            : "rgba(125, 211, 252, 0.08)";
        ctx.stroke();
      }

      // 3. Update & Draw Geometric Constellation Polyhedral Mesh
      for (let i = 0; i < nodes.length; i++) {
        const node = nodes[i];

        // Harmonic organic floating drift
        node.x += node.vx + Math.sin(time + node.phase) * 0.25;
        node.y += node.vy + Math.cos(time + node.phase) * 0.25;

        // Gentle mouse deflection
        const dx = node.x - mouse.x;
        const dy = node.y - mouse.y;
        const distToMouse = Math.sqrt(dx * dx + dy * dy);
        const maxDist = 200;

        if (distToMouse < maxDist) {
          const force = (1 - distToMouse / maxDist) * 1.5;
          node.x += (dx / distToMouse) * force;
          node.y += (dy / distToMouse) * force;
        }

        // Screen edge wrapping
        if (node.x < -30) node.x = width + 30;
        if (node.x > width + 30) node.x = -30;
        if (node.y < -30) node.y = height + 30;
        if (node.y > height + 30) node.y = -30;
      }

      // Connect nodes with crystalline geometric filaments & translucent facet planes
      const MAX_CONNECTION_DIST = width < 768 ? 120 : 165;

      for (let i = 0; i < nodes.length; i++) {
        const p1 = nodes[i];

        for (let j = i + 1; j < nodes.length; j++) {
          const p2 = nodes[j];
          const dist = Math.hypot(p1.x - p2.x, p1.y - p2.y);

          if (dist < MAX_CONNECTION_DIST) {
            const alpha = (1 - dist / MAX_CONNECTION_DIST) * 0.28 * p1.depth;

            ctx.beginPath();
            ctx.moveTo(p1.x, p1.y);
            ctx.lineTo(p2.x, p2.y);
            ctx.strokeStyle = `rgba(56, 189, 248, ${alpha})`;
            ctx.lineWidth = 0.85;
            ctx.stroke();

            // Form delicate 3-node polyhedral facets if a 3rd node is nearby
            for (let k = j + 1; k < nodes.length; k++) {
              const p3 = nodes[k];
              const dist2 = Math.hypot(p1.x - p3.x, p1.y - p3.y);
              const dist3 = Math.hypot(p2.x - p3.x, p2.y - p3.y);

              if (dist2 < MAX_CONNECTION_DIST * 0.8 && dist3 < MAX_CONNECTION_DIST * 0.8) {
                const facetAlpha = (1 - (dist + dist2 + dist3) / (MAX_CONNECTION_DIST * 2.4)) * 0.045;
                if (facetAlpha > 0.005) {
                  ctx.beginPath();
                  ctx.moveTo(p1.x, p1.y);
                  ctx.lineTo(p2.x, p2.y);
                  ctx.lineTo(p3.x, p3.y);
                  ctx.closePath();
                  ctx.fillStyle = `rgba(0, 210, 255, ${facetAlpha})`;
                  ctx.fill();
                }
              }
            }
          }
        }

        // Draw luminous node point
        const pulse = Math.sin(time * 2 + p1.phase) * 0.35 + 1;
        ctx.beginPath();
        ctx.arc(p1.x, p1.y, p1.radius * pulse, 0, Math.PI * 2);
        ctx.fillStyle = `rgba(186, 230, 253, ${0.45 * p1.depth})`;
        ctx.fill();

        // Subtle glow halo on primary foreground nodes
        if (p1.depth > 0.7) {
          ctx.beginPath();
          ctx.arc(p1.x, p1.y, p1.radius * pulse * 2.8, 0, Math.PI * 2);
          ctx.fillStyle = `rgba(0, 210, 255, ${0.12 * p1.depth})`;
          ctx.fill();
        }
      }

      animationFrameId = requestAnimationFrame(render);
    };

    render();

    return () => {
      cancelAnimationFrame(animationFrameId);
      window.removeEventListener("resize", handleResize);
      window.removeEventListener("mousemove", handleMouseMove);
      document.removeEventListener("mouseleave", handleMouseLeave);
    };
  }, []);

  return (
    <div className="geometric-ambient-canvas-wrapper" aria-hidden="true">
      <canvas ref={canvasRef} className="geometric-canvas" />
      <div className="geometric-ambient-vignette" />
    </div>
  );
}
