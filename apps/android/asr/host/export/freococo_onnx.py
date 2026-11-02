"""Exports freococo/myanmar_asr to one ONNX file the phone can run: 16 kHz mono audio in, syllable ids out.

The log-mel front end is part of the graph (STFT as a convolution with a fixed DFT basis), so the app only has
to collapse repeated ids, drop blanks and look the ids up in vocab.json.

usage: freococo_onnx.py <model_dir> <out.onnx> [--check <wav> ...]
--check compares the ONNX output with the PyTorch reference (transcribe.py) on each WAV.
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F
import torchaudio

N_FFT, HOP, N_MELS, SR = 400, 160, 80, 16000


class WaveToIds(torch.nn.Module):
    def __init__(self, model, cmvn_mean, cmvn_std):
        super().__init__()
        self.model = model
        n = torch.arange(N_FFT, dtype=torch.float64)
        k = torch.arange(N_FFT // 2 + 1, dtype=torch.float64)[:, None]
        window = torch.hann_window(N_FFT, periodic=True, dtype=torch.float64)
        angle = 2 * torch.pi * k * n / N_FFT
        # (2 * 201, 1, 400): real and imaginary DFT rows, windowed, as conv1d kernels.
        basis = torch.cat([window * torch.cos(angle), -window * torch.sin(angle)])[:, None, :]
        self.register_buffer("basis", basis.float())
        fb = torchaudio.functional.melscale_fbanks(
            N_FFT // 2 + 1, 0.0, SR / 2, N_MELS, SR, norm=None, mel_scale="htk"
        )
        self.register_buffer("fb", fb)  # (201, 80)
        self.register_buffer("mean", cmvn_mean)
        self.register_buffer("std", cmvn_std)

    def forward(self, wav):  # wav: (1, N) float32
        x = F.pad(wav[:, None, :], (N_FFT // 2, N_FFT // 2), mode="reflect")
        spec = F.conv1d(x, self.basis, stride=HOP)  # (1, 402, T)
        re, im = spec[:, : N_FFT // 2 + 1], spec[:, N_FFT // 2 + 1 :]
        power = (re * re + im * im).transpose(1, 2)  # (1, T, 201)
        mel = torch.log(torch.clamp(power @ self.fb, min=1e-5))
        mel = (mel - self.mean) / (self.std + 1e-9)
        x = self.model.frontend(mel)
        # One utterance per call, so no padding mask is needed.
        for blk in self.model.blocks:
            x = blk(x, key_pad_mask=None)
        logits = self.model.ctc_fc(self.model.enc_norm(x))
        return torch.argmax(logits, dim=-1)  # (1, T')


def load(model_dir):
    sys.path.insert(0, str(model_dir))
    from model import load_model

    cmvn = json.loads((model_dir / "cmvn.json").read_text())
    model = load_model(weights_path=str(model_dir / "model.safetensors"), device="cpu")
    return WaveToIds(model, torch.tensor(cmvn["mean"]), torch.tensor(cmvn["std"])).eval()


def collapse(ids, id2token):
    out, prev = [], -1
    for i in ids:
        if i != prev and i != 0:
            out.append(i)
        prev = i
    return " ".join(id2token[i] for i in out if id2token.get(i))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("model_dir", type=Path)
    ap.add_argument("out", type=Path)
    ap.add_argument("--check", nargs="*", default=[])
    a = ap.parse_args()

    net = load(a.model_dir)
    dummy = torch.zeros(1, SR * 3)
    torch.onnx.export(
        net, (dummy,), str(a.out), input_names=["audio"], output_names=["ids"],
        dynamic_axes={"audio": {1: "samples"}, "ids": {1: "frames"}}, opset_version=17, dynamo=False,
    )
    print("wrote", a.out, f"{a.out.stat().st_size / 1e6:.1f} MB")

    if a.check:
        import onnxruntime as ort
        import soundfile as sf
        from transcribe import BurmeseASR

        ref = BurmeseASR(model_dir=str(a.model_dir), device="cpu")
        sess = ort.InferenceSession(str(a.out), providers=["CPUExecutionProvider"])
        same = 0
        for w in a.check:
            audio, sr = sf.read(w, dtype="float32")
            assert sr == SR, f"{w} is {sr} Hz"
            got = collapse(sess.run(None, {"audio": audio[None, :]})[0][0].tolist(), ref.id2token)
            want = ref.transcribe(w)
            same += got == want
            if got != want:
                print(f"DIFF {Path(w).name}\n  torch: {want}\n  onnx:  {got}")
        print(f"identical transcripts: {same} / {len(a.check)}")


if __name__ == "__main__":
    with torch.inference_mode():
        main()
