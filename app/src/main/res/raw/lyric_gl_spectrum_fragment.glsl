#version 300 es
precision highp float;
in vec2 vUv;
out vec4 outColor;
uniform vec2 uResolution;
uniform float uTime;
uniform float uBands[16];

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}
vec3 rainbow(float t) {
    vec3 c0 = vec3(0.08,0.22,1.0);
    vec3 c1 = vec3(0.46,0.08,0.92);
    vec3 c2 = vec3(1.0,0.04,0.30);
    vec3 c3 = vec3(1.0,0.43,0.03);
    vec3 c4 = vec3(1.0,0.92,0.02);
    vec3 c5 = vec3(0.22,0.96,0.04);
    if (t < 0.2) return mix(c0,c1,t/0.2);
    if (t < 0.4) return mix(c1,c2,(t-0.2)/0.2);
    if (t < 0.6) return mix(c2,c3,(t-0.4)/0.2);
    if (t < 0.8) return mix(c3,c4,(t-0.6)/0.2);
    return mix(c4,c5,(t-0.8)/0.2);
}
void main() {
    vec2 uv = vUv;
    float baseY = 0.335;
    float totalW = 0.89;
    float cellW = totalW / 16.0;
    float xLocal = (uv.x - (1.0-totalW)*0.5) / cellW;
    float colF = floor(xLocal);
    int col = int(clamp(colF,0.0,15.0));
    float localX = fract(xLocal);
    float inColumn = step(0.0,xLocal) * step(xLocal,16.0);
    float level = clamp(uBands[col],0.025,1.0);
    level *= 0.96 + 0.04*sin(uTime*5.0 + float(col)*0.61);
    float stack = floor(1.0 + level*6.0);
    float segStep = 0.027;
    float segHeight = 0.021;
    float relY = baseY - uv.y;
    float segCoord = relY / segStep;
    float segIndex = floor(segCoord);
    float segLocalY = fract(segCoord);
    float bevel = 0.08 + 0.08*abs(segLocalY-0.5)*2.0;
    float insideX = step(bevel,localX)*step(localX,1.0-bevel);
    float insideY = step(0.0,segLocalY)*step(segLocalY,segHeight/segStep);
    float active = inColumn*insideX*insideY*step(segIndex,stack-1.0)*step(0.0,relY);
    float colorT = float(col)/15.0;
    vec3 barColor = rainbow(colorT);
    float reflectionY = uv.y-baseY;
    float reflCoord = reflectionY/segStep;
    float reflIndex = floor(reflCoord);
    float reflLocal = fract(reflCoord);
    float reflection = inColumn*insideX*step(0.0,reflLocal)*step(reflLocal,segHeight/segStep)
        *step(reflIndex,stack-1.0)*step(0.0,reflectionY)
        *exp(-reflectionY*8.5)*0.62;

    // Fine, animated bokeh particles. Sparse cell hashing avoids a CPU particle loop.
    vec2 grid = uv*vec2(34.0,46.0);
    vec2 cell = floor(grid);
    vec2 f = fract(grid);
    float rnd = hash21(cell);
    vec2 point = vec2(hash21(cell+7.1),hash21(cell+19.7));
    float radius = 0.035 + 0.055*hash21(cell+3.2);
    float d = length(f-point);
    float twinkle = 0.45 + 0.55*sin(uTime*2.0+rnd*18.0);
    float particle = (1.0-smoothstep(radius*0.2,radius,d))*step(0.83,rnd)*max(0.0,twinkle);

    // Horizontal white-hot neon floor with colored bloom.
    float lineD = abs(uv.y-baseY);
    float floorGlow = exp(-lineD*260.0);
    float floorCore = 1.0-smoothstep(0.0015,0.0045,lineD);
    vec3 floorColor = rainbow(clamp(uv.x,0.0,1.0));
    float peakY = baseY + 0.012 - stack*segStep;
    float peakTick = (1.0-smoothstep(0.001,0.003,abs(uv.y-peakY)))
        *step(0.08,localX)*step(localX,0.92)*inColumn;

    vec3 glow = barColor*(active*0.95 + reflection*0.75);
    float alpha = max(active*0.96,reflection*0.72);
    glow += floorColor*(floorGlow*0.60);
    alpha = max(alpha,floorGlow*0.58);
    glow += vec3(1.0)*(floorCore*0.96 + peakTick*0.95);
    alpha = max(alpha,max(floorCore,peakTick)*0.98);
    glow += rainbow(uv.x)*(particle*0.95);
    alpha = max(alpha,particle*0.82);

    // Output is a transparent visualizer layer; underlying media remains untouched.
    outColor = vec4(glow,clamp(alpha,0.0,1.0));
}