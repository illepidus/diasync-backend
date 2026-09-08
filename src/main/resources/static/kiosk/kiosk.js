const params = new URLSearchParams(location.search);
const DEFAULTS = {period: '1h', low: 70, high: 180, stale: 10, baseRadius: 6};
const USER_ID = params.get('userId') || 'demo';
const UNIT = params.get('unit') === 'mgdl' ? 'mgdl' : 'mmol';
const USE_CALIB = params.get('calibrations') !== 'false';
const LOW = parseFloat(params.get('low')) || DEFAULTS.low;
const HIGH = parseFloat(params.get('high')) || DEFAULTS.high;
const STALE_MIN = parseFloat(params.get('stale')) || DEFAULTS.stale;
const API_BASE = '/api/v1';
const LONG_POLL_TIMEOUT_MS = 45000;
const REQUEST_TIMEOUT_MS = LONG_POLL_TIMEOUT_MS + 10000;
const MAX_RETRY_DELAY_MS = 30000;
const PERIOD_MS = (() => {
    const m = /^(\d+)([smhd])$/.exec(params.get('period') || DEFAULTS.period);
    const mult = {s: 1e3, m: 6e4, h: 36e5, d: 864e5};
    return m ? +m[1] * (mult[m[2]] || 36e5) : 36e5;
})();

const COLORS = {
    stale: '#ff00ff',
    offline: '#ff0030',
    low: '#ff0030',
    high: '#ffc000',
    normal: '#ffffff',
    manual: '#ff0030',
    stroke: '#000000'
};

const centerTextPlugin = {
    id: 'centerText',
    afterDatasetsDraw(chart) {
        const {ctx, width, height} = chart;
        const text = chart.options.plugins.centerText?.text;
        const color = chart.options.plugins.centerText?.color || COLORS.stale;
        if (!text) return;

        ctx.save();
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillStyle = color;
        ctx.strokeStyle = COLORS.stroke;

        const fontFamily = 'Chakra Petch, sans-serif';
        let fontSize = height * 0.8;
        ctx.font = `bold ${fontSize}px ${fontFamily}`;

        let textMetrics = ctx.measureText(text);
        while ((textMetrics.width > width * 0.9 || fontSize > height * 0.8) && fontSize > 10) {
            fontSize -= 2;
            ctx.font = `bold ${fontSize}px ${fontFamily}`;
            textMetrics = ctx.measureText(text);
        }

        const offsetY = (textMetrics.actualBoundingBoxAscent - textMetrics.actualBoundingBoxDescent) / 2;
        const centerX = width / 2;
        const centerY = height / 2 + offsetY;

        ctx.lineWidth = fontSize * 0.025;
        ctx.strokeText(text, centerX, centerY);
        ctx.fillText(text, centerX, centerY);
        ctx.restore();
    }
};

const offlineStatusPlugin = {
    id: 'offlineStatus',
    afterDatasetsDraw(chart) {
        if (!chart.options.plugins.offlineStatus?.enabled) return;

        const {ctx, width, height} = chart;
        const text = 'OFFLINE';
        const color = COLORS.offline;
        const strokeColor = COLORS.stroke;
        const fontFamily = 'Chakra Petch, sans-serif';

        let fontSize = height * 0.1;
        ctx.font = `bold ${fontSize}px ${fontFamily}`;

        while (ctx.measureText(text).width > width * 0.95 && fontSize > 10) {
            fontSize -= 2;
            ctx.font = `bold ${fontSize}px ${fontFamily}`;
        }

        ctx.save();
        ctx.textAlign = 'center';
        ctx.textBaseline = 'top';
        ctx.fillStyle = color;
        ctx.strokeStyle = strokeColor;
        ctx.lineWidth = fontSize * 0.025;

        const centerX = width / 2;
        const offsetY = height * 0.025;

        ctx.strokeText(text, centerX, offsetY);
        ctx.fillText(text, centerX, offsetY);
        ctx.restore();
    }
};

const ctx = document.getElementById('bg').getContext('2d');
Chart.register(Chart.registry.getPlugin('annotation'), centerTextPlugin);
Chart.register(offlineStatusPlugin);

const sensorPoints = new Map();
const manualPoints = new Map();
let lastTimestamp = 0;
let isConnected = null;
let cursorTimestamp;
let cursorId = 0;
let activeRequest;
let pollingGeneration = 0;

function applyCalib(mgdl, cal) {
    return USE_CALIB && cal ? mgdl * cal.slope + cal.intercept : mgdl;
}

function toDisplay(mgdl, cal) {
    const v = applyCalib(mgdl, cal);
    return UNIT === 'mgdl' ? Math.round(v).toString() : (v / 18).toFixed(1);
}

function getColor(mgdl) {
    return mgdl < LOW ? COLORS.low : mgdl > HIGH ? COLORS.high : COLORS.normal;
}

let chart;

function initChart() {
    const radius = Math.max(2, Math.round(DEFAULTS.baseRadius * 3600000 / PERIOD_MS));
    chart = new Chart(ctx, {
        type: 'scatter',
        data: {
            datasets: [
                {
                    label: 'Sensor',
                    data: [],
                    pointRadius: radius,
                    pointHoverRadius: radius * 1.5,
                    pointStyle: 'circle',
                    backgroundColor: []
                },
                {
                    label: 'Manual',
                    data: [],
                    pointRadius: radius * 1.5,
                    pointHoverRadius: radius * 2,
                    pointStyle: 'rect',
                    backgroundColor: COLORS.manual,
                    borderColor: COLORS.normal,
                    borderWidth: radius / 2
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            animation: false,
            interaction: {mode: 'nearest', intersect: true},
            scales: {
                x: {display: false, type: 'time', min: () => Date.now() - PERIOD_MS, max: () => Date.now()},
                y: {display: false}
            },
            plugins: {
                legend: {display: false},
                tooltip: {
                    callbacks: {
                        title: items => new Date(items[0].raw.x).toLocaleTimeString('en-GB', {
                            hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit'
                        }),
                        label: ctx => {
                            const d = ctx.raw;
                            if (d.mgdl !== undefined) {
                                return [
                                    `Value: ${toDisplay(d.mgdl, d.calibration)} ${UNIT}`,
                                    `Sensor: ${d.sensorId}`,
                                    `Slope: ${d.calibration?.slope?.toFixed(3) || '-'}`,
                                    `Intercept: ${d.calibration?.intercept?.toFixed(3) || '-'}`
                                ];
                            } else {
                                return [`Manual: ${UNIT === 'mgdl' ? Math.round(d.y) : (d.y / 18).toFixed(1)} ${UNIT}`];
                            }
                        }
                    }
                },
                annotation: {
                    drawTime: 'beforeDatasetsDraw',
                    annotations: {
                        lowLine: {
                            display: false,
                            type: 'line',
                            yMin: LOW,
                            yMax: LOW,
                            borderColor: COLORS.low,
                            borderWidth: 2,
                            z: -10
                        },
                        highLine: {
                            display: false,
                            type: 'line',
                            yMin: HIGH,
                            yMax: HIGH,
                            borderColor: COLORS.high,
                            borderWidth: 2,
                            z: -10
                        }
                    }
                },
                centerText: {text: '???', color: COLORS.stale},
                offlineStatus: {enabled: false}
            }
        }
    });
}

function updateDisplay(timestamp, mgdl, cal) {
    const calibrated = applyCalib(mgdl, cal);
    chart.options.plugins.centerText.text = toDisplay(mgdl, cal);
    chart.options.plugins.centerText.color = getColor(calibrated);
    lastTimestamp = new Date(timestamp).getTime();
}

function updateChart() {
    const now = Date.now();
    const cutoff = now - PERIOD_MS;
    chart.options.scales.x.min = now - PERIOD_MS;
    chart.options.scales.x.max = now;

    prunePoints(sensorPoints, cutoff);
    prunePoints(manualPoints, cutoff);

    const recentSensor = [...sensorPoints.values()].sort((a, b) => new Date(a.x) - new Date(b.x));
    const recentManual = [...manualPoints.values()].sort((a, b) => new Date(a.x) - new Date(b.x));

    chart.data.datasets[0].data = recentSensor;
    chart.data.datasets[0].backgroundColor = recentSensor.map(p => p.backgroundColor);
    chart.data.datasets[1].data = recentManual;

    if (recentSensor.length || recentManual.length) {
        const allY = [...recentSensor.map(p => p.mgdl), ...recentManual.map(p => p.y)];
        const delta = 18;
        chart.options.scales.y.min = Math.min(...allY, LOW) - delta;
        chart.options.scales.y.max = Math.max(...allY, HIGH) + delta;
    }

    if (!lastTimestamp || (now - lastTimestamp) / 60000 > STALE_MIN) {
        chart.options.plugins.centerText.text = '???';
        chart.options.plugins.centerText.color = COLORS.stale;
    }

    const showLines = recentSensor.length > 0 || recentManual.length > 0;
    chart.options.plugins.annotation.annotations.lowLine.display = showLines;
    chart.options.plugins.annotation.annotations.highLine.display = showLines;

    chart.update('none');
}

function prunePoints(points, cutoff) {
    for (const [timestamp, point] of points) {
        if (new Date(point.x).getTime() < cutoff) {
            points.delete(timestamp);
        }
    }
}

function pushSensorPoint(ts, sg) {
    const calibrated = applyCalib(sg.mgdl, sg.calibration);
    sensorPoints.set(ts, {
        x: ts,
        y: calibrated,
        mgdl: sg.mgdl,
        sensorId: sg.sensorId,
        calibration: sg.calibration,
        backgroundColor: getColor(calibrated)
    });
}

function markDisconnected() {
    if (isConnected === false) return;
    isConnected = false;
    chart.options.plugins.offlineStatus.enabled = true;
    chart.update('none');
}

function markConnected() {
    if (isConnected === true) return;
    isConnected = true;
    chart.options.plugins.offlineStatus.enabled = false;
    chart.update('none');
}

function applyDataPoints(points) {
    for (const point of points) {
        const timestampMs = new Date(point.timestamp).getTime();
        if (!Number.isFinite(timestampMs)) {
            throw new Error('The server returned a data point with an invalid timestamp');
        }

        const sensorGlucose = point.sensorGlucose;
        const manualGlucose = point.manualGlucose;
        if (sensorGlucose?.mgdl != null) {
            pushSensorPoint(point.timestamp, sensorGlucose);
            if (timestampMs >= lastTimestamp) {
                updateDisplay(point.timestamp, sensorGlucose.mgdl, sensorGlucose.calibration);
            }
        }
        if (manualGlucose?.mgdl != null) {
            manualPoints.set(point.timestamp, {x: point.timestamp, y: manualGlucose.mgdl});
        }
    }
}

function advanceCursor(points) {
    for (let index = points.length - 1; index >= 0; index -= 1) {
        const point = points[index];
        if (!point.updateTimestamp || !Number.isSafeInteger(point.id)) {
            continue;
        }

        if (!Number.isFinite(new Date(point.updateTimestamp).getTime())) {
            throw new Error('The server returned a data point with an invalid update timestamp');
        }

        cursorTimestamp = point.updateTimestamp;
        cursorId = point.id;
        return;
    }
}

async function fetchDataPoints(url, timeoutMs) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), timeoutMs);
    activeRequest = controller;

    try {
        const response = await fetch(url, {
            cache: 'no-store',
            headers: {'Accept': 'application/json'},
            signal: controller.signal
        });
        if (!response.ok) {
            throw new Error(`Request failed with HTTP ${response.status}`);
        }

        const result = await response.json();
        if (!Array.isArray(result)) {
            throw new Error('The server returned an invalid response');
        }
        return result;
    } finally {
        clearTimeout(timeout);
        if (activeRequest === controller) {
            activeRequest = undefined;
        }
    }
}

async function loadInitial() {
    const to = new Date().toISOString();
    cursorTimestamp = new Date(Date.now() - PERIOD_MS).toISOString();
    const query = new URLSearchParams({userId: USER_ID, from: cursorTimestamp, to});
    const points = await fetchDataPoints(`${API_BASE}/getDataPoints?${query}`, REQUEST_TIMEOUT_MS);
    applyDataPoints(points);
}

function longPollUrl() {
    const query = new URLSearchParams({
        userId: USER_ID,
        since: cursorTimestamp,
        sinceId: cursorId.toString(),
        timeoutMs: LONG_POLL_TIMEOUT_MS.toString()
    });
    return `${API_BASE}/getDataPointsLongPoll?${query}`;
}

function retryDelay(attempt) {
    const exponentialDelay = Math.min(1000 * 2 ** Math.min(attempt, 5), MAX_RETRY_DELAY_MS);
    return exponentialDelay * (0.75 + Math.random() * 0.5);
}

function waitForRetry(delay) {
    return new Promise(resolve => {
        const finish = () => {
            clearTimeout(timeout);
            window.removeEventListener('online', finish);
            resolve();
        };
        const timeout = setTimeout(finish, delay);
        window.addEventListener('online', finish, {once: true});
    });
}

async function pollForever(generation) {
    let failures = 0;

    while (generation === pollingGeneration) {
        const startedAt = Date.now();
        try {
            const points = await fetchDataPoints(longPollUrl(), REQUEST_TIMEOUT_MS);
            if (generation !== pollingGeneration) return;

            applyDataPoints(points);
            advanceCursor(points);
            markConnected();
            failures = 0;

            if (points.length === 0 && Date.now() - startedAt < 1000) {
                await waitForRetry(1000);
            }
        } catch (error) {
            if (generation !== pollingGeneration) return;

            markDisconnected();
            if (failures === 0 || failures % 10 === 0) {
                console.error('Long polling failed; retrying:', error);
            }
            await waitForRetry(retryDelay(failures));
            failures += 1;
        }
    }
}

async function start() {
    const generation = ++pollingGeneration;

    try {
        await loadInitial();
        if (generation !== pollingGeneration) return;

        markConnected();
    } catch (error) {
        if (generation !== pollingGeneration) return;

        markDisconnected();
        console.error('Failed to load initial data; continuing with long polling:', error);
    }

    await pollForever(generation);
}

initChart();
setInterval(updateChart, 500);
window.addEventListener('pagehide', () => {
    pollingGeneration += 1;
    activeRequest?.abort();
});
window.addEventListener('pageshow', event => {
    if (event.persisted) {
        start();
    }
});
start();
