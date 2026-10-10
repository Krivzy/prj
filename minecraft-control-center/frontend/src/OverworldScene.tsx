import {useEffect, useId, useRef, useState} from 'react';
import type * as Three from 'three';
import './overworld-scene.css';

type SceneController = {
  setActivity: (visible: boolean, reduced: boolean) => void;
  resize: () => void;
  dispose: () => void;
};
type Block = {x: number; y: number; z: number; scale?: [number, number, number]; tint?: number};

const vertexShader = `
  varying vec2 vUv;
  void main() {
    vUv = uv;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
  }
`;

// Two-triangle surfaces, no displacement, raymarching, postprocessing or simulation.
const portalShader = `
  uniform float uTime;
  uniform vec3 uDeep;
  uniform vec3 uLight;
  varying vec2 vUv;
  void main() {
    vec2 p = vUv - 0.5;
    float swirl = sin(p.x * 8.0 + sin(p.y * 7.0 - uTime * 0.38) * 1.6
      + uTime * 0.22);
    float field = sin(p.y * 12.0 + swirl * 1.9 - uTime * 0.45);
    float rim = pow(max(abs(p.x) * 2.0, abs(p.y) * 2.0), 7.0);
    vec3 color = mix(uDeep, uLight, 0.23 + 0.34 * smoothstep(-1.0, 1.0, field));
    color += uLight * (rim * 0.68 + 0.07);
    gl_FragColor = vec4(color, 0.96);
    #include <tonemapping_fragment>
    #include <colorspace_fragment>
  }
`;

const waterShader = `
  uniform float uTime;
  uniform float uFall;
  uniform vec3 uDeep;
  uniform vec3 uLight;
  varying vec2 vUv;
  void main() {
    float stream = sin(vUv.x * 27.0 + sin(vUv.y * 9.0) * 0.65);
    float ripple = sin(vUv.y * mix(19.0, 37.0, uFall) + uTime * mix(0.8, 2.7, uFall));
    float stripe = smoothstep(0.74, 1.0, stream) * 0.26;
    float crest = smoothstep(0.84, 1.0, ripple) * 0.2;
    vec3 color = mix(uDeep, uLight, 0.28 + stripe + crest);
    gl_FragColor = vec4(color, 0.86);
    #include <tonemapping_fragment>
    #include <colorspace_fragment>
  }
`;

/** Owns its renderer and resources. It has no pointer, keyboard or scroll controls. */
function createOverworld(
  T: typeof Three,
  host: HTMLDivElement,
  compact: boolean,
  onReady: () => void,
  onFailure: () => void,
): SceneController {
  let disposed = false;
  let broken = false;
  let visible = false;
  let reduced = false;
  let elapsed = 0;
  let previous = 0;
  let renderedAt = 0;
  let ready = false;
  let renderer: Three.WebGLRenderer | undefined;
  const geometries = new Set<Three.BufferGeometry>();
  const materials = new Set<Three.Material>();
  const textures = new Set<Three.Texture>();
  const instances = new Set<Three.InstancedMesh>();
  const textureCache = new Map<string, Three.Texture>();
  const animatedMaterials: Three.ShaderMaterial[] = [];
  const world = new T.Group();
  const scene = new T.Scene();
  const camera = new T.OrthographicCamera(-8, 8, 6, -6, 0.1, 70);
  const pose = new T.Object3D();
  const tint = new T.Color();
  const debris: {x: number; y: number; z: number; size: number; phase: number}[] = [];
  let debrisMesh: Three.InstancedMesh | undefined;
  let dropsMesh: Three.InstancedMesh | undefined;
  const dropSeeds = Array.from({length: 13}, (_, i) => ({
    x: (i % 4 - 1.5) * 0.16,
    phase: i * 0.61803398875 % 1,
    z: (i % 3) * 0.07,
  }));

  function stop() {
    renderer?.setAnimationLoop(null);
    previous = 0;
    renderedAt = 0;
  }

  function contextLost(event: Event) {
    event.preventDefault();
    fail();
  }

  function dispose() {
    if (disposed) return;
    disposed = true;
    stop();
    renderer?.domElement.removeEventListener('webglcontextlost', contextLost);
    instances.forEach(mesh => mesh.dispose());
    geometries.forEach(geometry => geometry.dispose());
    materials.forEach(material => material.dispose());
    textures.forEach(texture => texture.dispose());
    scene.clear();
    if (renderer) {
      renderer.dispose();
      renderer.forceContextLoss();
      renderer.domElement.remove();
    }
  }

  function fail() {
    if (broken || disposed) return;
    broken = true;
    stop();
    onFailure();
    // Shader errors can arrive during render: release the owned context after it unwinds.
    queueMicrotask(dispose);
  }

  function geometry<G extends Three.BufferGeometry>(value: G): G {
    geometries.add(value);
    return value;
  }

  function material<M extends Three.Material>(value: M): M {
    materials.add(value);
    return value;
  }

  function render() {
    if (disposed || broken || !visible || !renderer) return;
    renderer.render(scene, camera);
    if (broken) return;
    if (!ready) {
      ready = true;
      const info = renderer.info.render;
      renderer.domElement.dataset.drawCalls = String(info.calls);
      renderer.domElement.dataset.triangles = String(info.triangles);
      onReady();
    }
  }

  function texture(name: string) {
    const existing = textureCache.get(name);
    if (existing) return existing;
    const value = new T.TextureLoader().load(`/game-assets/textures/block/${name}.png`,
      loaded => {
        if (disposed) loaded.dispose();
        else render();
      }, undefined,
      () => {
        // A missing optional texture keeps the colored voxel material usable.
        if (disposed) return;
        materials.forEach(item => {
          if (item instanceof T.MeshStandardMaterial && item.map === value) {
            item.map = null;
            item.needsUpdate = true;
          }
        });
        value.dispose();
        render();
      });
    value.colorSpace = T.SRGBColorSpace;
    value.magFilter = T.NearestFilter;
    value.minFilter = T.NearestMipmapNearestFilter;
    value.anisotropy = 1;
    textures.add(value);
    textureCache.set(name, value);
    return value;
  }

  function surface(name: string, color: string, extra: Three.MeshStandardMaterialParameters = {}) {
    return material(new T.MeshStandardMaterial({
      map: texture(name), color, roughness: 0.97, metalness: 0, ...extra,
    }));
  }

  try {
    renderer = new T.WebGLRenderer({alpha: true, antialias: true, powerPreference: 'low-power'});
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5));
    renderer.setClearColor(0x000000, 0);
    renderer.outputColorSpace = T.SRGBColorSpace;
    renderer.toneMapping = T.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.05;
    renderer.debug.onShaderError = fail;
    renderer.domElement.className = 'overworld-scene__canvas';
    renderer.domElement.setAttribute('aria-hidden', 'true');
    renderer.domElement.addEventListener('webglcontextlost', contextLost);
    host.appendChild(renderer.domElement);
    scene.add(world);
    world.position.y = -0.25;
    camera.position.set(11, 9, 14);
    camera.lookAt(0, 0.55, 0);

    scene.add(new T.HemisphereLight('#bed7ed', '#18232c', 1.8));
    const key = new T.DirectionalLight('#fff0cc', 3.0);
    key.position.set(-4, 9, 6);
    scene.add(key);
    const rim = new T.DirectionalLight('#6cc7c7', 2.0);
    rim.position.set(5, 5, -5);
    scene.add(rim);
    const portalLight = new T.PointLight('#9276ff', 17, 7, 2);
    portalLight.position.set(2.4, 1.7, 1.1);
    world.add(portalLight);

    const cube = geometry(new T.BoxGeometry(1, 1, 1));
    const plane = geometry(new T.PlaneGeometry(1, 1));
    const unit = 0.7;
    function blocks(data: Block[], mats: Three.Material | Three.Material[], parent: Three.Object3D = world) {
      const mesh = new T.InstancedMesh(cube, mats, data.length);
      instances.add(mesh);
      data.forEach((block, index) => {
        pose.position.set(block.x, block.y, block.z);
        pose.rotation.set(0, 0, 0);
        pose.scale.set(...(block.scale ?? [unit, unit, unit]));
        pose.updateMatrix();
        mesh.setMatrixAt(index, pose.matrix);
        tint.setScalar(block.tint ?? 1);
        mesh.setColorAt(index, tint);
      });
      mesh.instanceMatrix.needsUpdate = true;
      if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
      mesh.computeBoundingSphere();
      parent.add(mesh);
      return mesh;
    }

    const dirt = surface('dirt', '#897663');
    const grassSide = surface('grass_block_side', '#9eae8a');
    const grassTop = surface('grass_block_top', '#619e70');
    const stone = surface('stone', '#789094');
    const deepStone = surface('stone', '#53646e');
    const moss = surface('moss_block', '#679675');
    const top: Block[] = [];
    const soil: Block[] = [];
    const rock: Block[] = [];
    const deep: Block[] = [];

    // Regular voxel strata make a readable landform, with a cutaway facing the camera.
    for (let x = -6; x <= 6; x++) {
      for (let z = -4; z <= 4; z++) {
        const contour = (x / 6.0) ** 2 + (z / 4.3) ** 2;
        if (contour > 1 || (x < -4 && z > 2) || (x > 4 && z < -2)) continue;
        const shade = 0.9 + ((x * 7 + z * 11 + 99) % 5) * 0.024;
        const river = x === 0 && z >= -1;
        top.push({x: x * unit, y: river ? -0.17 : 0, z: z * unit, tint: shade});
        soil.push({x: x * unit, y: -0.7, z: z * unit, tint: shade});
        if (contour < 0.86) rock.push({x: x * unit, y: -1.4, z: z * unit, tint: shade});
        if (contour < 0.66) rock.push({x: x * unit, y: -2.1, z: z * unit, tint: shade * 0.95});
        if (contour < 0.41) deep.push({x: x * unit, y: -2.8, z: z * unit, tint: shade});
        if (contour < 0.16 && x !== 1) deep.push({x: x * unit, y: -3.5, z: z * unit, tint: shade});
      }
    }
    blocks(top, [grassSide, grassSide, grassTop, dirt, grassSide, grassSide]);
    blocks(soil, dirt);
    blocks(rock, stone);
    blocks(deep, deepStone);

    const spruceLog = surface('spruce_log', '#8a847b');
    const spruceTop = surface('spruce_log_top', '#a69b83');
    const birchLog = surface('birch_log', '#d7ded4');
    const birchTop = surface('birch_log_top', '#c7c4ae');
    const spruceLeaves = surface('spruce_leaves', '#488d70', {alphaTest: 0.45});
    const birchLeaves = surface('birch_leaves', '#75a96b', {alphaTest: 0.45});
    const trunks: Block[] = Array.from({length: 7}, (_, i) => ({x: -2.55, y: 0.7 + i * 0.59, z: -1.4, scale: [0.55, 0.59, 0.55]}));
    blocks(trunks, [spruceLog, spruceLog, spruceTop, spruceTop, spruceLog, spruceLog]);
    const spruce: Block[] = [];
    for (let tier = 0; tier < 5; tier++) {
      const radius = tier < 2 ? 2 : tier < 4 ? 1 : 0;
      for (let x = -radius; x <= radius; x++) {
        for (let z = -radius; z <= radius; z++) {
          if (Math.abs(x) + Math.abs(z) > radius + 1 || (x === 0 && z === 0 && tier < 4)) continue;
          spruce.push({x: -2.55 + x * 0.55, y: 2.35 + tier * 0.65, z: -1.4 + z * 0.55,
            scale: [0.58, 0.62, 0.58], tint: 0.84 + ((x + z + 9) % 3) * 0.08});
        }
      }
    }
    blocks(spruce, spruceLeaves);
    blocks(Array.from({length: 5}, (_, i) => ({x: -2.85, y: 0.67 + i * 0.58, z: 1.15, scale: [0.43, 0.58, 0.43]})),
      [birchLog, birchLog, birchTop, birchTop, birchLog, birchLog]);
    const birch: Block[] = [];
    for (let y = 0; y < 3; y++) {
      for (let x = -1; x <= 1; x++) {
        for (let z = -1; z <= 1; z++) {
          if (y === 2 && Math.abs(x) + Math.abs(z) > 1) continue;
          birch.push({x: -2.85 + x * 0.6, y: 2.7 + y * 0.58, z: 1.15 + z * 0.6,
            scale: [0.64, 0.62, 0.64], tint: 0.9 + ((x + z + 5) % 3) * 0.05});
        }
      }
    }
    blocks(birch, birchLeaves);

    // A freestanding obsidian frame: the opening stays readable at compact sizes.
    const portal = new T.Group();
    portal.position.set(2.15, 0.37, -0.35);
    portal.rotation.y = -0.12;
    world.add(portal);
    const obsidian = surface('obsidian', '#747086', {roughness: 0.74});
    const portalBlocks: Block[] = [];
    for (let x = 0; x < 4; x++) {
      for (let y = 0; y < 5; y++) {
        if (x === 0 || x === 3 || y === 0 || y === 4) {
          portalBlocks.push({x: (x - 1.5) * 0.64, y: y * 0.64, z: 0, scale: [0.64, 0.64, 0.54]});
        }
      }
    }
    blocks(portalBlocks, obsidian, portal);
    const portalMaterial = material(new T.ShaderMaterial({
      uniforms: {uTime: {value: 0}, uDeep: {value: new T.Color('#382571')}, uLight: {value: new T.Color('#bf8aff')}},
      vertexShader, fragmentShader: portalShader, side: T.DoubleSide,
    }));
    animatedMaterials.push(portalMaterial);
    const gate = new T.Mesh(plane, portalMaterial);
    gate.position.set(0, 1.28, 0.03);
    gate.scale.set(1.28, 1.92, 1);
    portal.add(gate);

    const glow = material(new T.ShaderMaterial({
      uniforms: {uColor: {value: new T.Color('#c8aaff')}}, vertexShader,
      fragmentShader: `
        uniform vec3 uColor;
        varying vec2 vUv;
        void main() {
          float glow = 1.0 - smoothstep(0.12, 0.62, length((vUv - 0.5) * vec2(0.82, 1.0)));
          gl_FragColor = vec4(uColor, glow * 0.18);
          #include <tonemapping_fragment>
          #include <colorspace_fragment>
        }
      `,
      transparent: true, depthWrite: false, blending: T.AdditiveBlending, side: T.DoubleSide,
    }));
    const halo = new T.Mesh(plane, glow);
    halo.position.set(0, 1.28, -0.31);
    halo.scale.set(2.9, 3.55, 1);
    portal.add(halo);

    function water(fall: boolean) {
      const value = material(new T.ShaderMaterial({
        uniforms: {uTime: {value: 0}, uFall: {value: fall ? 1 : 0},
          uDeep: {value: new T.Color('#187f99')}, uLight: {value: new T.Color('#b0efea')}},
        vertexShader, fragmentShader: waterShader,
        transparent: true, side: T.DoubleSide, depthWrite: false,
      }));
      animatedMaterials.push(value);
      return value;
    }
    const river = new T.Mesh(plane, water(false));
    river.rotation.x = -Math.PI / 2;
    river.scale.set(0.61, 3.65, 1);
    river.position.set(0, 0.2, 1.06);
    world.add(river);
    const fall = new T.Mesh(plane, water(true));
    fall.scale.set(0.64, 3.38, 1);
    fall.position.set(0, -1.44, 2.82);
    world.add(fall);
    const fallSide = new T.Mesh(plane, fall.material);
    fallSide.rotation.y = Math.PI / 2;
    fallSide.scale.set(0.22, 3.38, 1);
    fallSide.position.set(0.31, -1.44, 2.72);
    world.add(fallSide);
    const foam = material(new T.MeshBasicMaterial({color: '#bcf3ec', transparent: true, opacity: 0.65}));
    blocks([{x: 0, y: 0.19, z: 2.7, scale: [0.67, 0.045, 0.29]},
      {x: 0, y: -3.18, z: 2.83, scale: [0.91, 0.055, 0.54]}], foam);
    dropsMesh = blocks(dropSeeds.map(seed => ({x: seed.x, y: -3.3 - seed.phase, z: 2.85 + seed.z,
      scale: [0.055, 0.11, 0.055]})), foam);
    dropsMesh.frustumCulled = false;

    // Small surface details repeat in one draw call per material.
    blocks([{x: 1.4, y: 0.47, z: 1.4, scale: [0.5, 0.26, 0.46]},
      {x: -1.35, y: 0.47, z: 2.15, scale: [0.31, 0.24, 0.36]},
      {x: 3.4, y: 0.46, z: 0.8, scale: [0.43, 0.24, 0.48]},
      {x: 0.9, y: 0.48, z: -1.6, scale: [0.52, 0.28, 0.45]}], moss);
    const lantern = surface('glowstone', '#ffe6b4', {emissive: '#cc903e', emissiveIntensity: 0.7});
    blocks([{x: 1.45, y: 0.59, z: 1.28, scale: [0.35, 0.35, 0.35]}], lantern);
    const lanternLight = new T.PointLight('#ffd09a', 3, 3, 2);
    lanternLight.position.set(1.45, 0.8, 1.28);
    world.add(lanternLight);

    for (let i = 0; i < 9; i++) {
      const angle = i * 2.399963;
      debris.push({x: Math.cos(angle) * (3.6 + i % 3 * 0.6),
        y: -1.9 - i % 4 * 0.56, z: Math.sin(angle) * 2.4,
        size: 0.16 + i % 3 * 0.055, phase: i * 0.77});
    }
    debrisMesh = blocks(debris.map(item => ({...item, scale: [item.size, item.size, item.size]})), stone);
    debrisMesh.frustumCulled = false;

    function updatePose() {
      world.rotation.y = Math.sin(elapsed * 0.12) * 0.035;
      world.position.y = -0.25 + Math.sin(elapsed * 0.45) * 0.07;
      animatedMaterials.forEach(value => {value.uniforms.uTime.value = elapsed;});
      debris.forEach((item, index) => {
        pose.position.set(item.x, item.y + Math.sin(elapsed * 0.6 + item.phase) * 0.12, item.z);
        pose.rotation.set(item.phase * 0.2 + elapsed * 0.06, item.phase + elapsed * 0.08, 0.1);
        pose.scale.setScalar(item.size);
        pose.updateMatrix();
        debrisMesh!.setMatrixAt(index, pose.matrix);
      });
      debrisMesh!.instanceMatrix.needsUpdate = true;
      dropSeeds.forEach((seed, index) => {
        const progress = (seed.phase + elapsed * 0.7) % 1;
        pose.position.set(seed.x, -3.2 - progress * 1.15, 2.85 + seed.z);
        pose.rotation.set(0, 0, 0);
        pose.scale.set(0.055 * (1 - progress * 0.6), 0.11, 0.055);
        pose.updateMatrix();
        dropsMesh!.setMatrixAt(index, pose.matrix);
      });
      dropsMesh!.instanceMatrix.needsUpdate = true;
    }

    function frame(time: number) {
      if (!visible || reduced || disposed || broken) return;
      if (!previous) previous = time;
      const delta = Math.min((time - previous) / 1000, 0.05);
      previous = time;
      elapsed += delta;
      if (time - renderedAt < 1000 / 30) return;
      renderedAt = time;
      updatePose();
      render();
    }

    function resize() {
      if (disposed || broken || !renderer) return;
      const width = Math.max(host.clientWidth, 1);
      const height = Math.max(host.clientHeight, 1);
      const aspect = width / height;
      // Compact scenes live in a hero with vertical overscan; leave room for the canopy.
      const halfHeight = Math.max(compact ? 6.6 : 5.5, 6.35 / aspect);
      camera.left = -halfHeight * aspect;
      camera.right = halfHeight * aspect;
      camera.top = halfHeight;
      camera.bottom = -halfHeight;
      camera.updateProjectionMatrix();
      renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5));
      renderer.setSize(width, height, false);
      render();
    }

    return {
      resize,
      setActivity(nextVisible, nextReduced) {
        if (disposed || broken) return;
        visible = nextVisible;
        reduced = nextReduced;
        stop();
        renderer!.domElement.dataset.motion = visible && !reduced ? 'running' : 'still';
        if (visible) {
          updatePose();
          resize();
          if (!reduced && !broken) renderer!.setAnimationLoop(frame);
        }
      },
      dispose,
    };
  } catch (error) {
    dispose();
    throw error;
  }
}

function StaticOverworld({id}: {id: string}) {
  return <svg className="overworld-scene__fallback" viewBox="0 0 700 550" fill="none" focusable="false">
    <defs>
      <linearGradient id={`${id}-rock`} x1="250" y1="310" x2="400" y2="490" gradientUnits="userSpaceOnUse"><stop stopColor="#42565c"/><stop offset="1" stopColor="#172b35"/></linearGradient>
      <linearGradient id={`${id}-portal`} x1="415" y1="200" x2="490" y2="305" gradientUnits="userSpaceOnUse"><stop stopColor="#6955b7"/><stop offset=".5" stopColor="#bf9bf3"/><stop offset="1" stopColor="#503385"/></linearGradient>
      <linearGradient id={`${id}-water`} x1="346" y1="340" x2="346" y2="490" gradientUnits="userSpaceOnUse"><stop stopColor="#8dede1"/><stop offset="1" stopColor="#3d8aab" stopOpacity="0"/></linearGradient>
    </defs>
    <path d="M157 313 340 212 551 326 350 437Z" fill="#224435"/>
    <path d="M157 313 350 412 350 485 318 468 318 447 265 421 265 400 210 373 210 356 157 329Z" fill={`url(#${id}-rock)`}/>
    <path d="M350 412 551 326 551 348 508 365 508 388 449 414 449 441 394 466 394 481 350 497Z" fill="#20353c"/>
    <path d="M157 313 340 221 551 326 350 418Z" fill="#3f6952"/>
    <path d="M157 313 350 418 350 433 157 327Z" fill="#365946"/>
    <path d="M350 418 551 326 551 340 350 433Z" fill="#294437"/>
    <path d="M313 262 347 280 372 393 349 405 326 297 294 280Z" fill="#4faaa8"/>
    <path d="M349 405 371 394 371 478 349 491Z" fill={`url(#${id}-water)`}/>
    <path d="M349 405 357 403 357 481" stroke="#bbfff0" strokeOpacity=".5" strokeWidth="3"/>
    <path d="M237 308V165L254 156V315Z" fill="#81745b"/>
    <path d="m184 245 62-34 61 34-61 32Z" fill="#2c6150"/><path d="m184 245 62 32v24l-62-33Z" fill="#244f42"/><path d="m246 277 61-33v24l-61 33Z" fill="#204b40"/>
    <path d="m197 199 49-26 48 26-48 26Z" fill="#39745c"/><path d="m197 199 49 26v27l-49-26Z" fill="#2a5c4b"/><path d="m246 225 48-26v27l-48 26Z" fill="#214c40"/>
    <path d="m217 153 29-16 28 16-28 15Z" fill="#51866a"/><path d="m217 153 29 15v33l-29-16Z" fill="#32664e"/><path d="m246 168 28-15v33l-28 15Z" fill="#2a5948"/>
    <path d="M211 345v-78l13-7v92Z" fill="#b8bcb0"/><path d="m181 269 37-20 40 20-39 22Z" fill="#789765"/><path d="m181 269 38 22v31l-38-22Z" fill="#517b58"/><path d="m219 291 39-22v31l-39 22Z" fill="#37634b"/>
    <path d="m409 311 10-118 86 45-7 117Z" fill="#262735"/><path d="m420 193 18-8 85 45-18 8Z" fill="#515166"/><path d="m505 238 18-8-7 115-18 10Z" fill="#393a51"/>
    <path d="m436 218 51 27-5 80-51-27Z" fill={`url(#${id}-portal)`}/><path d="m445 232 32 21-4 55-32-17Z" stroke="#e8c1ff" strokeOpacity=".4" strokeWidth="2"/>
    <path d="m165 391 14-8 15 8-14 8Z" fill="#5a7c79"/><path d="m165 391 15 8v16l-15-8Z" fill="#3b5b60"/><path d="m180 399 14-8v16l-14 8Z" fill="#294650"/>
    <path d="m478 437 11-6 12 6-11 6Z" fill="#597675"/><path d="m478 437 12 6v13l-12-6Z" fill="#3a5862"/>
  </svg>;
}

/** Decorative voxel scenery; the surrounding UI owns all meaningful content. */
export default function OverworldScene({className = '', compact = false}: {className?: string; compact?: boolean}) {
  const host = useRef<HTMLDivElement>(null);
  const [phase, setPhase] = useState<'loading' | 'ready' | 'fallback'>('loading');
  const id = useId().replace(/:/g, '');

  useEffect(() => {
    const element = host.current;
    if (!element) return;
    let alive = true;
    let intersecting = false;
    let loading = false;
    let controller: SceneController | undefined;
    const preference = window.matchMedia('(prefers-reduced-motion: reduce)');
    setPhase('loading');

    function sync() {
      if (!alive) return;
      const visible = intersecting && !document.hidden;
      if (controller) controller.setActivity(visible, preference.matches);
      else if (visible && !loading) {
        loading = true;
        void import('three').then(T => {
          if (!alive) return;
          if (!intersecting || document.hidden) {loading = false; return;}
          controller = createOverworld(T, element!, compact,
            () => {if (alive) setPhase('ready');},
            () => {if (alive) setPhase('fallback');});
          controller.setActivity(intersecting && !document.hidden, preference.matches);
        }).catch(() => {if (alive) setPhase('fallback');});
      }
    }

    const observer = typeof IntersectionObserver === 'undefined' ? undefined : new IntersectionObserver(entries => {
      intersecting = entries.some(entry => entry.isIntersecting);
      sync();
    }, {threshold: 0});
    const resize = new ResizeObserver(() => controller?.resize());
    resize.observe(element);
    if (observer) observer.observe(element);
    else {intersecting = true; sync();}
    document.addEventListener('visibilitychange', sync);
    preference.addEventListener('change', sync);

    return () => {
      alive = false;
      observer?.disconnect();
      resize.disconnect();
      document.removeEventListener('visibilitychange', sync);
      preference.removeEventListener('change', sync);
      controller?.dispose();
    };
  }, [compact]);

  return <div className={`overworld-scene ${compact ? 'overworld-scene--compact' : ''} ${className}`}
    data-scene-state={phase} aria-hidden="true">
    <div className="overworld-scene__aura"/>
    <StaticOverworld id={id}/>
    <div className="overworld-scene__host" ref={host}/>
    <div className="overworld-scene__vignette"/>
  </div>;
}
