"""
같은 장면을 '일반' 과 어떤 룩으로 찍은 사진 두 장에서 그 룩을 33³ .cube 로 뽑습니다.
앱의 라이카 룩 4종(assets/luts/leica)을 이걸로 만들었습니다.

    pip install numpy scipy opencv-python-headless pillow
    python3 fit_look.py 일반.jpg 룩.jpg "Leica Teal" leica_teal.cube [비교.jpg]

1. 두 사진을 sRGB 로 바꾸고 (아이폰 사진은 Display P3) 긴 변 1000px 로 줄임
2. SIFT 특징점 + RANSAC 으로 룩 사진을 일반 사진 위에 겹침 (조금 움직여 찍어도 됨)
3. 흐리게 한 뒤 평평한 곳의 픽셀 쌍을 뽑아 (움직인 사람 같은 건 robust 손실로 무시) 아래 모델을 피팅
     l = 밝기,  y = N(l) + c(l)·R(θ)·(x − l),  y *= 1 − a·smooth(0.25, 1.6, d)
     N: 채널별 단조 톤 곡선 (회색이 어떤 색·밝기가 되는지) · c: 밝기별 채도 · θ: 색상 회전 · a: 비네팅
   한 장면에는 없는 색도 많아서, 색은 '채도·색상 회전' 정도로만 자유롭게 두어 처음 보는 색에서도 무난하게 함
4. 룩 사진이 노출 자체가 어두운 경우가 많아서, 중간톤이 어두워진 양은 DARK_KEEP(기본 0.5) 만큼만 남김
비네팅 값은 앱 단위(a / 0.6)로 출력합니다 (Presets.kt 의 vignette).
"""
import io, os, sys
import numpy as np, cv2
from PIL import Image, ImageCms
from scipy.optimize import least_squares

DARK_KEEP = float(os.environ.get("DARK_KEEP", "0.5"))
W = np.array([0.2126, 0.7152, 0.0722])
KT, KC = 14, 6
kt, kc = np.linspace(0, 1, KT), np.linspace(0, 1, KC)


def load_srgb(path):
    im = Image.open(path)
    icc = im.info.get("icc_profile")
    if icc:
        im = ImageCms.profileToProfile(im, ImageCms.ImageCmsProfile(io.BytesIO(icc)), ImageCms.createProfile("sRGB"), outputMode="RGB")
    im = im.convert("RGB")
    im.thumbnail((1000, 1000), Image.LANCZOS)
    return np.asarray(im).astype(np.float32) / 255.0


def clahe_gray(im):
    g = cv2.cvtColor((im * 255).astype(np.uint8), cv2.COLOR_RGB2GRAY)
    return cv2.createCLAHE(3.0, (8, 8)).apply(g)


def align(A, B):
    sift = cv2.SIFT_create(4000)
    ka, da = sift.detectAndCompute(clahe_gray(A), None)
    kb, db = sift.detectAndCompute(clahe_gray(B), None)
    good = [m for m, n in cv2.BFMatcher().knnMatch(db, da, k=2) if m.distance < 0.75 * n.distance]
    H, _ = cv2.findHomography(np.float32([kb[g.queryIdx].pt for g in good]), np.float32([ka[g.trainIdx].pt for g in good]), cv2.RANSAC, 3.0)
    return H


def smooth(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def to_lin(v):
    v = np.clip(v, 0, 1)
    return np.where(v <= 0.04045, v / 12.92, ((v + 0.055) / 1.055) ** 2.4)


def to_srgb(v):
    v = np.clip(v, 0, 1)
    return np.where(v <= 0.0031308, v * 12.92, 1.055 * v ** (1 / 2.4) - 0.055)


def knots(p):  # 단조 증가 곡선: 시작값 + softplus 증가분
    return np.concatenate([[p[0]], p[0] + np.cumsum(np.log1p(np.exp(p[1:])) / (KT - 1))])


def interp_ext(z, ys):
    out = np.interp(np.clip(z, 0, 1), kt, ys)
    s0 = (ys[1] - ys[0]) / kt[1]
    s1 = (ys[-1] - ys[-2]) / kt[1]
    return np.where(z < 0, ys[0] + z * s0, np.where(z > 1, ys[-1] + (z - 1) * s1, out))


def unpack(th):
    ty = np.stack([knots(th[k * KT:(k + 1) * KT]) for k in range(3)])
    cy = np.exp(th[3 * KT:3 * KT + KC])
    return ty, cy, th[3 * KT + KC], th[3 * KT + KC + 1]


AX = np.ones(3) / np.sqrt(3)
SKEW = np.array([[0, -AX[2], AX[1]], [AX[2], 0, -AX[0]], [-AX[1], AX[0], 0]])


def rot(theta):  # 회색 축 둘레로 색상 회전
    return np.eye(3) + np.sin(theta) * SKEW + (1 - np.cos(theta)) * SKEW @ SKEW


def model(th, X, D=None, tone=None, chroma_gain=None):
    ty, cy, theta, a = unpack(th)
    l = X @ W
    N = np.stack([(tone or (lambda z, y: interp_ext(z, y)))(l, ty[k]) for k in range(3)], 1)
    c = np.interp(np.clip(l, 0, 1), kc, cy)
    if chroma_gain is not None:
        c = np.clip(c, 0.75, 1.45) * chroma_gain(l)
    Y = N + c[:, None] * ((X - l[:, None]) @ rot(theta).T)
    if D is not None:
        Y = Y * (1 - a * smooth(0.25, 1.6, D))[:, None]
    return Y


def resid(th, X, Y, D):
    ty, cy, theta, a = unpack(th)
    n = np.sqrt(len(X))
    return np.concatenate([(model(th, X, D) - Y).ravel(),
                           np.diff(ty, 2, axis=1).ravel() * 0.3 * n,
                           np.diff(np.log(cy), 2) * 0.5 * n,
                           (np.log(cy) - np.log(cy).mean()) * 0.05 * n,
                           [theta * 0.05 * n]])


def samples(A, B, H):
    h, w = A.shape[:2]
    Bw = cv2.warpPerspective(B, H, (w, h))
    inside = cv2.warpPerspective(np.ones(B.shape[:2], np.float32), H, (w, h)) > 0.999
    yy, xx = np.mgrid[0:B.shape[0], 0:B.shape[1]].astype(np.float32)
    nx, ny = (xx - B.shape[1] / 2) / (B.shape[1] / 2), (yy - B.shape[0] / 2) / (B.shape[0] / 2)
    Dw = cv2.warpPerspective(nx * nx + ny * ny, H, (w, h))   # 룩 사진 기준 중심 거리² (비네팅)
    As, Bs = cv2.GaussianBlur(A, (0, 0), 2.0), cv2.GaussianBlur(Bw, (0, 0), 2.0)
    g = As @ W.astype(np.float32)
    grad = np.hypot(cv2.Sobel(g, cv2.CV_32F, 1, 0, ksize=3), cv2.Sobel(g, cv2.CV_32F, 0, 1, ksize=3))
    valid = (cv2.erode(inside.astype(np.uint8), np.ones((15, 15), np.uint8)) > 0) & (grad < 0.08)
    X, Y, D, v = As.reshape(-1, 3), Bs.reshape(-1, 3), Dw.ravel(), valid.ravel()
    chroma = np.abs(X - (X @ W)[:, None]).max(1)
    rng = np.random.default_rng(0)
    col, neu = np.flatnonzero(v & (chroma > 0.06)), np.flatnonzero(v & (chroma <= 0.06))
    idx = np.concatenate([rng.choice(col, min(12000, len(col)), replace=False), rng.choice(neu, min(20000, len(neu)), replace=False)])
    return X[idx], Y[idx], D[idx], Bw, Dw


def main(normal, look, title, out_cube, compare=None):
    A, B = load_srgb(normal), load_srgb(look)
    X, Y, D, Bw, Dw = samples(A, B, align(A, B))
    th0 = np.zeros(3 * KT + KC + 2)
    for k in range(3):
        th0[k * KT + 1:(k + 1) * KT] = np.log(np.expm1(1.0))
    th = least_squares(resid, th0, args=(X, Y, D), loss="soft_l1", f_scale=0.03, max_nfev=600).x
    ty3, cy, theta, a = unpack(th)
    err = np.abs(model(th, X, D) - Y).mean(0) * 255

    ty = W @ ty3
    mid = 0.46
    out_mid = np.interp(mid, kt, ty)
    target = out_mid + (1 - DARK_KEEP) * max(0.0, mid - out_mid)
    gamma = np.log(to_lin(target)) / np.log(to_lin(out_mid))
    tone = lambda l, y: to_srgb(to_lin(interp_ext(l, y)) ** gamma)
    gain = lambda l: np.clip((tone(l, ty) + 0.02) / (np.clip(interp_ext(l, ty), 0, 1) + 0.02), 1.0, 1.6)

    n = 33
    v = np.linspace(0, 1, n)
    b_, g_, r_ = np.meshgrid(v, v, v, indexing="ij")   # .cube 는 빨강이 가장 빨리 바뀜
    lut = np.clip(model(th, np.stack([r_.ravel(), g_.ravel(), b_.ravel()], 1), None, tone, gain), 0, 1)
    with open(out_cube, "w") as f:
        f.write(f'# FOFilter · {title}\nTITLE "{title}"\nLUT_3D_SIZE {n}\nDOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 1.0 1.0 1.0\n')
        f.write("\n".join(f"{p:.4f} {q:.4f} {r:.4f}" for p, q, r in lut) + "\n")
    print(f"{title}: 평균 오차 RGB {err.round(1)}/255 · 중간톤 {mid} → {out_mid:.3f} → {target:.3f} · "
          f"색상 회전 {np.degrees(theta):.1f}° · 앱 비네팅 {a / 0.6:.2f}")

    if compare:  # 일반 | 룩(겹친 것) | 피팅 | 밝기 맞춘 최종
        fit = np.clip(model(th, A.reshape(-1, 3), Dw.ravel()).reshape(A.shape), 0, 1)
        final = np.clip(model(th, A.reshape(-1, 3), Dw.ravel(), tone, gain).reshape(A.shape), 0, 1)
        Image.fromarray((np.concatenate([A, Bw, fit, final], 1) * 255).astype(np.uint8)).save(compare, quality=88)


if __name__ == "__main__":
    if len(sys.argv) < 5:
        sys.exit(__doc__)
    main(*sys.argv[1:6])
