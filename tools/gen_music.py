#!/usr/bin/env python3
import argparse
import math
import os
import wave

import numpy as np

SR = 44100

CHORD_TYPES = {
    "maj": [0, 4, 7],
    "min": [0, 3, 7],
    "maj7": [0, 4, 7, 11],
    "min7": [0, 3, 7, 10],
    "add9": [0, 4, 7, 14],
    "minadd9": [0, 3, 7, 14],
    "6": [0, 4, 7, 9],
    "sus2": [0, 2, 7],
}

SCALES = {
    "major": [0, 2, 4, 5, 7, 9, 11],
    "penta_major": [0, 2, 4, 7, 9],
    "penta_minor": [0, 3, 5, 7, 10],
    "natural_minor": [0, 2, 3, 5, 7, 8, 10],
}

TIMBRES = {
    "music_box": {"harmonics": [(1, 1.0), (2, 0.28), (3, 0.12), (4.2, 0.05)], "attack": 0.004, "decay": 1.6},
    "piano": {"harmonics": [(1, 1.0), (2, 0.42), (3, 0.18), (4, 0.07)], "attack": 0.006, "decay": 2.4},
    "epiano": {"harmonics": [(1, 1.0), (2, 0.18), (2.01, 0.1)], "attack": 0.01, "decay": 2.0},
    "bell": {"harmonics": [(1, 1.0), (2.76, 0.4), (5.4, 0.18), (8.9, 0.06)], "attack": 0.003, "decay": 2.8},
    "pluck": {"harmonics": [], "attack": 0.0, "decay": 0.0},
    "pad": {"harmonics": [(1, 1.0), (1.005, 0.7), (0.5, 0.35), (2, 0.22)], "attack": 0.4, "decay": 0.0},
    "bass": {"harmonics": [(1, 1.0), (0.5, 0.5), (2, 0.12)], "attack": 0.01, "decay": 0.9},
}


def midi_to_freq(m):
    return 440.0 * 2.0 ** ((m - 69) / 12.0)


def adsr_env(n, attack, decay, sustain_level=0.0, release=0.08):
    env = np.zeros(n)
    a = max(1, int(attack * SR))
    r = max(1, int(release * SR))
    d = max(1, n - a - r)
    env[:a] = np.linspace(0.0, 1.0, a)
    if decay > 0:
        env[a:a + d] = np.exp(-np.linspace(0.0, decay, d)) * (1.0 - sustain_level) + sustain_level
    else:
        env[a:a + d] = 1.0
    env[a + d:] = 0.0
    tail = env[a + d - r:a + d] if a + d - r >= 0 else env[:max(1, n - r)]
    env[-r:] *= np.linspace(1.0, 0.0, r)
    return env


def render_tone(freq, dur, timbre, velocity=1.0, rng=None):
    t = TIMBRES[timbre]
    n = int(dur * SR)
    if timbre == "pluck":
        return karplus_strong(freq, n, velocity, rng if rng is not None else np.random.default_rng(0))
    x = np.zeros(n)
    tt = np.arange(n) / SR
    for mult, gain in t["harmonics"]:
        if mult * freq < SR / 2 - 200:
            x += gain * np.sin(2 * np.pi * freq * mult * tt)
    sustain = 0.0 if t["decay"] > 0 else 1.0
    env = adsr_env(n, t["attack"], t["decay"], sustain_level=sustain)
    return (x * env * velocity).astype(np.float64)


def karplus_strong(freq, n, velocity, rng):
    period = max(2, int(SR / freq))
    buf = rng.uniform(-1, 1, period)
    out = np.zeros(n)
    idx = 0
    prev = 0.0
    for i in range(n):
        cur = buf[idx]
        out[i] = cur
        nxt = buf[(idx + 1) % period]
        buf[idx] = 0.996 * 0.5 * (cur + nxt)
        idx = (idx + 1) % period
    return out * velocity


def render_saw_pad(freq, dur, velocity=1.0, detune=0.004):
    n = int(dur * SR)
    tt = np.arange(n) / SR
    x = np.zeros(n)
    for det in (-detune, 0.0, detune):
        f = freq * (1.0 + det)
        x += np.sin(2 * np.pi * f * tt) * 0.6 + 0.4 * np.sin(2 * np.pi * f * 2 * tt) * 0.4
    cutoff_env = 1.0 / (1.0 + np.exp(-(tt / max(dur, 0.01) * 4.0 - 1.0)))
    alpha = 0.02 + 0.06 * cutoff_env
    y = np.zeros(n)
    acc = 0.0
    for i in range(n):
        acc += alpha[i] * (x[i] - acc)
        y[i] = acc
    env = adsr_env(n, 0.35, 0.0, sustain_level=1.0, release=0.4)
    return y * env * velocity * 0.6


def one_pole_lowpass(x, alpha):
    y = np.empty_like(x)
    acc = 0.0
    for i in range(len(x)):
        acc += alpha * (x[i] - acc)
        y[i] = acc
    return y


def place(mix, sig, start_s, pan=0.0):
    start = int(start_s * SR)
    if start >= mix.shape[1]:
        return
    end = min(mix.shape[1], start + len(sig))
    seg = sig[:end - start]
    lg = math.cos((pan + 1) * math.pi / 4)
    rg = math.sin((pan + 1) * math.pi / 4)
    mix[0, start:end] += seg * lg
    mix[1, start:end] += seg * rg


def reverb(mix, taps):
    wet = np.zeros_like(mix)
    for dt, gain in taps:
        shift = int(dt * SR)
        if shift < mix.shape[1]:
            wet[:, shift:] += mix[:, :-shift] * gain
    wet[0] = one_pole_lowpass(wet[0], 0.18)
    wet[1] = one_pole_lowpass(wet[1], 0.15)
    return mix + wet * 0.5


RHYTHMS = {
    "flowing": [[(0, 1.0), (1, 0.5), (1.5, 0.5), (2, 1.0), (3, 0.5), (3.5, 0.5)],
                [(0, 0.75), (0.75, 0.25), (1, 0.5), (2, 0.75), (3, 1.0)],
                [(0, 1.5), (2, 0.5), (2.5, 0.5), (3, 1.0)]],
    "sparse": [[(0, 1.5), (2, 1.0)], [(0, 2.0), (3, 1.0)], [(1, 1.0), (2.5, 1.5)]],
    "waltz": [[(0, 1.0), (1, 0.5), (2, 1.5)], [(0, 0.5), (0.5, 0.5), (1, 1.0), (2, 1.0)],
              [(0, 2.0), (2, 1.0)]],
    "bounce": [[(0, 0.5), (0.5, 0.5), (1, 0.5), (1.5, 0.5), (2, 0.5), (2.5, 0.5), (3, 1.0)],
               [(0, 0.75), (1, 0.25), (1.25, 0.75), (2, 0.5), (2.5, 0.5), (3, 1.0)],
               [(0, 1.0), (1, 0.5), (1.5, 0.25), (1.75, 0.25), (2, 1.0), (3, 1.0)]],
    "arp": [[(i * 0.5, 0.5) for i in range(8)]],
}


def pick_melody_note(rng, chord_pcs, scale_pcs, prev):
    candidates = []
    if prev is None:
        prev = 72
    for octave in (-12, 0, 12):
        for pc in chord_pcs:
            candidates.append((pc + octave, 0.62))
        for pc in scale_pcs:
            candidates.append((pc + octave, 0.16))
    best = sorted(candidates, key=lambda c: abs(c[0] - prev))[:6]
    weights = np.array([c[1] for c in best], dtype=float)
    weights /= weights.sum()
    idx = rng.choice(len(best), p=weights)
    return best[idx][0] + 60


def gen_track(cfg):
    rng = np.random.default_rng(cfg["seed"])
    bpm = cfg["bpm"]
    beats_per_bar = cfg.get("beats_per_bar", 4)
    beat_s = 60.0 / bpm
    bar_s = beat_s * beats_per_bar
    prog = cfg["progression"]
    melody = cfg.get("melody")
    if melody is not None:
        bars = len(prog) * cfg.get("melody_repeats", 2)
    else:
        bars = len(prog) * cfg.get("prog_repeats", 4)
    tonic = cfg["tonic"]
    scale_pcs = SCALES[cfg["scale"]]
    tail_s = 3.0
    total_s = bars * bar_s + tail_s
    mix = np.zeros((2, int(total_s * SR)))
    layers = cfg["layers"]
    melody_timbre = cfg.get("melody_timbre", "piano")
    if melody is not None:
        cycle_beats = len(prog) * beats_per_bar
        for rep in range(cfg.get("melody_repeats", 2)):
            beat_pos = rep * cycle_beats + cfg.get("melody_offset", 0.0)
            for note, dur_beats in melody:
                if note is not None:
                    vel = 0.45 + 0.2 * rng.random()
                    sig = render_tone(midi_to_freq(note), max(0.3, dur_beats * beat_s * 1.5), melody_timbre, vel, rng)
                    place(mix, sig, beat_pos * beat_s, pan=cfg.get("melody_pan", 0.1))
                beat_pos += dur_beats
    else:
        rhythm_set = RHYTHMS[cfg["rhythm"]]
        prev_note = None
        motif_len = len(prog)
        melody_plan = []
        for bar in range(bars):
            root, ctype = prog[bar % len(prog)]
            chord_pcs = sorted({(tonic + root + iv) % 12 for iv in CHORD_TYPES[ctype]})
            if bar < motif_len or bar % motif_len == 0 or cfg["rhythm"] == "arp":
                pattern = rhythm_set[rng.integers(len(rhythm_set))]
                notes = []
                for beat, dur_beats in pattern:
                    if rng.random() < cfg.get("rest_prob", 0.12) and beat > 0:
                        notes.append(None)
                        continue
                    note = pick_melody_note(rng, chord_pcs, scale_pcs, prev_note)
                    prev_note = note
                    notes.append((beat, dur_beats, note))
                if bar < motif_len:
                    melody_plan = notes
            else:
                notes = melody_plan
                prev_note = notes[-1][2] if notes and notes[-1] else prev_note
            for item in notes:
                if item is None:
                    continue
                beat, dur_beats, note = item
                start = bar * bar_s + beat * beat_s
                vel = 0.5 + 0.25 * rng.random()
                if cfg["rhythm"] == "arp":
                    seq = chord_pcs + [chord_pcs[0] + 12]
                    note = 60 + seq[int((beat * 2)) % len(seq)] % 12 + (12 if int(beat * 2) >= len(chord_pcs) else 0)
                    vel = 0.32
                sig = render_tone(midi_to_freq(note), max(0.25, dur_beats * beat_s * 1.6), melody_timbre, vel, rng)
                place(mix, sig, start, pan=cfg.get("melody_pan", 0.12))
    for bar in range(bars):
        root, ctype = prog[bar % len(prog)]
        root_midi = tonic + root
        chord_midis = [root_midi + iv for iv in CHORD_TYPES[ctype]]
        start = bar * bar_s
        if "bass" in layers:
            bass_note = root_midi - 24
            sig = render_tone(midi_to_freq(bass_note), bar_s * 0.9, "bass", 0.55)
            place(mix, sig, start, pan=-0.05)
            if cfg.get("bass_fifth") and beats_per_bar >= 4:
                sig2 = render_tone(midi_to_freq(bass_note + 7), bar_s * 0.4, "bass", 0.4)
                place(mix, sig2, start + bar_s * 0.5, pan=-0.05)
        if "pad" in layers:
            for iv in CHORD_TYPES[ctype][:3]:
                m = root_midi + iv
                sig = render_saw_pad(midi_to_freq(m), bar_s * 1.05, velocity=0.16)
                place(mix, sig, start, pan=-0.35 + 0.35 * ((iv * 7) % 3))
        if "harmony" in layers and cfg.get("rhythm") != "arp":
            for j, iv in enumerate(CHORD_TYPES[ctype]):
                m = root_midi + iv
                sig = render_tone(midi_to_freq(m), bar_s * 0.95, "epiano", 0.14)
                place(mix, sig, start + (0.02 * j), pan=0.3)
    mix = reverb(mix, cfg.get("taps", [(0.19, 0.32), (0.31, 0.22), (0.47, 0.13)]))
    loop_n = int(bars * bar_s * SR)
    tail = mix[:, loop_n:]
    mix = mix[:, :loop_n]
    n_tail = tail.shape[1]
    mix[:, :n_tail] += tail
    peak = np.max(np.abs(mix))
    if peak > 0:
        mix = mix / peak * 0.72
    mix = np.tanh(mix * 1.15) * 0.92
    return mix


def write_wav(path, mix):
    pcm = (mix * 32767).astype(np.int16)
    interleaved = np.stack([pcm[0], pcm[1]], axis=1).flatten()
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(interleaved.tobytes())


PACHELBEL_CANON = [(78, 2), (76, 2), (74, 2), (73, 2), (71, 2), (69, 2), (71, 2), (73, 2),
                   (74, 2), (73, 2), (71, 2), (69, 2), (67, 2), (66, 2), (67, 2), (64, 2)]

JINGLE_BELLS = [(64, 1), (64, 1), (64, 2), (64, 1), (64, 1), (64, 2), (64, 1), (67, 1),
                (60, 1.5), (62, .5), (64, 4), (65, 1), (65, 1), (65, 1.5), (65, .5),
                (65, 1), (64, 1), (64, 1), (64, .5), (64, .5), (64, 1), (62, 1), (62, 1),
                (64, 1), (62, 2), (67, 2), (64, 1), (64, 1), (64, 2), (64, 1), (64, 1),
                (64, 2), (64, 1), (67, 1), (60, 1.5), (62, .5), (64, 4), (65, 1), (65, 1),
                (65, 1.5), (65, .5), (65, 1), (64, 1), (64, 1), (64, .5), (64, .5),
                (67, 1), (67, 1), (65, 1), (62, 1), (60, 4)]

ODE_TO_JOY = [(64, 1), (64, 1), (65, 1), (67, 1), (67, 1), (65, 1), (64, 1), (62, 1),
              (60, 1), (60, 1), (62, 1), (64, 1), (64, 1.5), (62, .5), (62, 2),
              (64, 1), (64, 1), (65, 1), (67, 1), (67, 1), (65, 1), (64, 1), (62, 1),
              (60, 1), (60, 1), (62, 1), (64, 1), (62, 1.5), (60, .5), (60, 2)]

XINNIANHAO = [(60, 1), (60, 1), (60, 1), (67, 1), (64, 1), (64, 1), (64, 1), (72, 1),
              (60, 1), (64, 1), (67, 1), (67, 1), (64, 1), (62, 1), (60, 2)]

GREENSLEEVES = [(69, 1), (72, 1), (74, 1), (76, 1), (77, .5), (76, .5), (74, 1),
                (71, 1), (67, .5), (69, .5), (71, 1), (72, 1), (69, 1), (69, .5), (68, .5),
                (69, .5), (71, .5), (68, 1), (64, 1),
                (69, 1), (72, 1), (74, 1), (76, 1), (77, .5), (76, .5), (74, 1),
                (71, 1), (67, .5), (69, .5), (71, 1), (69, 1), (71, 1), (72, 1),
                (71, .5), (69, .5), (68, 1), (69, 1)]

TRACKS = [
    {"id": "birthday_01", "seed": 101, "tonic": 60, "scale": "major", "bpm": 100, "beats_per_bar": 3,
     "progression": [(0, "maj"), (7, "maj"), (7, "maj"), (0, "maj"), (0, "maj"), (7, "maj"), (5, "maj"), (7, "maj"), (0, "maj"), (0, "maj")],
     "rhythm": "waltz", "prog_repeats": 2,
     "melody_timbre": "music_box", "layers": ["bass", "harmony"]},
    {"id": "wedding_01", "seed": 202, "tonic": 62, "scale": "major", "bpm": 72,
     "progression": [(0, "maj"), (7, "maj"), (9, "min"), (4, "min"), (5, "maj"), (0, "maj"), (5, "maj"), (7, "maj")],
     "melody": PACHELBEL_CANON, "melody_repeats": 2,
     "melody_timbre": "piano", "layers": ["bass", "pad"]},
    {"id": "spring_festival_01", "seed": 112, "tonic": 60, "scale": "major", "bpm": 120,
     "progression": [(0, "maj"), (7, "maj"), (5, "maj"), (0, "maj")],
     "melody": XINNIANHAO, "melody_repeats": 4,
     "melody_timbre": "music_box", "layers": ["bass", "harmony"], "bass_fifth": True},
    {"id": "christmas_01", "seed": 113, "tonic": 67, "scale": "major", "bpm": 112, "beats_per_bar": 3,
     "progression": [(0, "maj"), (5, "maj"), (7, "maj"), (7, "maj"), (0, "maj"), (5, "maj"), (7, "maj"), (0, "maj")],
     "rhythm": "waltz", "prog_repeats": 2,
     "melody_timbre": "bell", "layers": ["bass", "harmony"]},
    {"id": "qixi_01", "seed": 114, "tonic": 60, "scale": "penta_major", "bpm": 76,
     "progression": [(9, "min"), (0, "maj"), (4, "min"), (9, "min"), (9, "min"), (0, "maj"), (7, "maj"), (0, "maj")],
     "rhythm": "flowing", "prog_repeats": 2,
     "melody_timbre": "pluck", "layers": ["bass", "pad"]},
    {"id": "valentine_01", "seed": 115, "tonic": 57, "scale": "natural_minor", "bpm": 88, "beats_per_bar": 3,
     "progression": [(0, "min"), (7, "min"), (10, "maj"), (0, "min"), (7, "maj"), (0, "min"), (7, "min"), (10, "maj"), (0, "min"), (7, "maj")],
     "melody": GREENSLEEVES, "melody_repeats": 2,
     "melody_timbre": "pluck", "layers": ["bass", "pad"]},
    {"id": "travel_01", "seed": 303, "tonic": 67, "scale": "penta_major", "bpm": 112,
     "progression": [(0, "maj"), (9, "min"), (5, "maj"), (7, "maj")], "rhythm": "flowing",
     "melody_timbre": "pluck", "layers": ["bass", "harmony"], "bass_fifth": True, "prog_repeats": 4},
    {"id": "coffee_01", "seed": 404, "tonic": 65, "scale": "major", "bpm": 92,
     "progression": [(0, "maj7"), (4, "min7"), (9, "min7"), (7, "maj7")], "rhythm": "flowing",
     "melody_timbre": "epiano", "layers": ["bass", "harmony"], "prog_repeats": 5, "rest_prob": 0.2},
    {"id": "lounge_01", "seed": 505, "tonic": 57, "scale": "natural_minor", "bpm": 84,
     "progression": [(0, "minadd9"), (8, "maj7"), (3, "maj7"), (10, "6")], "rhythm": "sparse",
     "melody_timbre": "epiano", "layers": ["bass", "pad"], "prog_repeats": 5, "rest_prob": 0.3},
    {"id": "morning_01", "seed": 606, "tonic": 60, "scale": "major", "bpm": 100,
     "progression": [(0, "maj"), (9, "min"), (5, "maj"), (7, "sus2")], "rhythm": "arp",
     "melody_timbre": "music_box", "layers": ["bass", "pad"], "prog_repeats": 4},
    {"id": "night_01", "seed": 707, "tonic": 58, "scale": "major", "bpm": 60,
     "progression": [(0, "maj"), (9, "min"), (8, "maj"), (7, "sus2")], "rhythm": "sparse",
     "melody_timbre": "music_box", "layers": ["bass", "pad"], "prog_repeats": 4, "rest_prob": 0.35},
    {"id": "festive_01", "seed": 808, "tonic": 60, "scale": "major", "bpm": 130,
     "progression": [(0, "maj"), (0, "maj"), (5, "maj"), (0, "maj"), (5, "maj"), (0, "maj"), (7, "maj"), (7, "maj"),
                     (0, "maj"), (0, "maj"), (5, "maj"), (0, "maj"), (5, "maj"), (0, "maj"), (7, "maj"), (0, "maj")],
     "melody": JINGLE_BELLS, "melody_repeats": 1,
     "melody_timbre": "bell", "layers": ["bass", "harmony"], "bass_fifth": True},
    {"id": "classical_01", "seed": 909, "tonic": 60, "scale": "major", "bpm": 96,
     "progression": [(0, "maj"), (7, "maj"), (0, "maj"), (7, "maj"), (0, "maj"), (7, "maj"), (0, "maj"), (0, "maj")],
     "melody": ODE_TO_JOY, "melody_repeats": 2,
     "melody_timbre": "piano", "layers": ["bass", "pad"]},
    {"id": "nature_01", "seed": 111, "tonic": 64, "scale": "penta_minor", "bpm": 70,
     "progression": [(0, "minadd9"), (8, "add9"), (3, "add9"), (10, "add9")], "rhythm": "sparse",
     "melody_timbre": "pluck", "layers": ["pad"], "prog_repeats": 5, "rest_prob": 0.4},
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    parser.add_argument("--only", default=None)
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)
    for cfg in TRACKS:
        if args.only and cfg["id"] != args.only:
            continue
        mix = gen_track(cfg)
        dur = mix.shape[1] / SR
        path = os.path.join(args.out, f"music_{cfg['id']}.wav")
        write_wav(path, mix)
        rms = float(np.sqrt(np.mean(mix ** 2)))
        print(f"{cfg['id']}: {dur:.1f}s peak={np.max(np.abs(mix)):.2f} rms={rms:.3f} -> {path}")


if __name__ == "__main__":
    main()
