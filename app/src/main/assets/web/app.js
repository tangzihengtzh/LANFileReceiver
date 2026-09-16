/* ============================================================
   局域网文件接收 · 上传页面脚本
   纯原生 JavaScript，无任何外部依赖，完全离线可用
   ============================================================ */

(function () {
  'use strict';

  var gate = document.getElementById('gate');
  var gateInput = document.getElementById('token-input');
  var gateError = document.getElementById('gate-error');
  var gateSubmit = document.getElementById('gate-submit');

  var authenticated = false;

  var dropzone = document.getElementById('dropzone');
  var fileInput = document.getElementById('file-input');
  var selectBtn = document.getElementById('select-btn');
  var queueSection = document.getElementById('queue-section');
  var queueList = document.getElementById('queue-list');
  var queueSummary = document.getElementById('queue-summary');
  var clearBtn = document.getElementById('clear-btn');
  var statusBox = document.getElementById('status');
  var statusText = document.getElementById('status-text');
  var footerInfo = document.getElementById('footer-info');
  var photosSection = document.getElementById('photos-section');
  var photoGrid = document.getElementById('photo-grid');
  var photosSummary = document.getElementById('photos-summary');
  var photosEmpty = document.getElementById('photos-empty');
  var photosRefresh = document.getElementById('photos-refresh');
  var photosDownloadAll = document.getElementById('photos-download-all');

  var ICONS = {
    waiting:
      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" ' +
      'stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="8.5"/>' +
      '<path d="M12 7.5V12l3 1.8"/></svg>',
    uploading:
      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" ' +
      'stroke-linecap="round" stroke-linejoin="round"><path d="M12 16V4"/>' +
      '<path d="m7.5 8.5 4.5-4.5 4.5 4.5"/><path d="M4 15v3.5A1.5 1.5 0 0 0 5.5 20h13a1.5 1.5 0 0 0 1.5-1.5V15"/></svg>',
    completed:
      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" ' +
      'stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="8.5"/>' +
      '<path d="m8.4 12.3 2.5 2.5 4.7-5"/></svg>',
    failed:
      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" ' +
      'stroke-linecap="round" stroke-linejoin="round"><path d="M12 4.5 3.2 19.5h17.6z"/>' +
      '<path d="M12 10v4"/><path d="M12 17h.01"/></svg>'
  };

  var queue = [];
  var running = false;
  var sequence = 0;

  /* ------------------------------------------------ Token 验证 */

  function showGate(message) {
    authenticated = false;
    if (!gate.hidden) {
      if (message) {
        gateError.textContent = message;
        gateError.hidden = false;
      }
      return;
    }
    gate.hidden = false;
    document.body.classList.add('locked');
    if (message) {
      gateError.textContent = message;
      gateError.hidden = false;
    }
    setTimeout(function () { gateInput.focus(); }, 60);
  }

  function hideGate() {
    if (authenticated) return;
    authenticated = true;
    gate.hidden = true;
    document.body.classList.remove('locked');
    gateError.hidden = true;
    gateInput.value = '';
    photoSignature = '';
    refreshStatus();
    refreshPhotos();
  }

  function submitToken() {
    var token = (gateInput.value || '').replace(/\D/g, '');
    if (token.length !== 4) {
      gateError.textContent = '请输入 4 位数字';
      gateError.hidden = false;
      return;
    }

    gateSubmit.disabled = true;
    fetch('/api/auth', {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain; charset=utf-8' },
      body: token
    })
      .then(function (response) {
        return response.json()
          .catch(function () { return {}; })
          .then(function (data) {
            return { ok: response.ok, data: data };
          });
      })
      .then(function (result) {
        gateSubmit.disabled = false;
        if (result.ok) {
          hideGate();
          return;
        }
        gateError.textContent = (result.data && result.data.message) || 'Token 不正确';
        gateError.hidden = false;
        gateInput.value = '';
        gateInput.focus();
      })
      .catch(function () {
        gateSubmit.disabled = false;
        gateError.textContent = '无法连接手机，请检查网络';
        gateError.hidden = false;
      });
  }

  gateSubmit.addEventListener('click', submitToken);

  gateInput.addEventListener('input', function () {
    gateInput.value = gateInput.value.replace(/\D/g, '').slice(0, 4);
    gateError.hidden = true;
    if (gateInput.value.length === 4) submitToken();
  });

  gateInput.addEventListener('keydown', function (event) {
    if (event.key === 'Enter') submitToken();
  });

  /* ------------------------------------------------ 工具函数 */

  function formatSize(bytes) {
    if (!bytes || bytes < 0) return '0 B';
    if (bytes < 1024) return bytes + ' B';
    var kb = bytes / 1024;
    if (kb < 1024) return kb.toFixed(1) + ' KB';
    var mb = kb / 1024;
    if (mb < 1024) return mb.toFixed(2) + ' MB';
    return (mb / 1024).toFixed(2) + ' GB';
  }

  function formatSpeed(bytesPerSecond) {
    if (!bytesPerSecond || bytesPerSecond <= 0) return '';
    return formatSize(bytesPerSecond) + '/s';
  }

  function formatDuration(seconds) {
    if (!isFinite(seconds) || seconds < 0) return '';
    if (seconds < 60) return Math.round(seconds) + ' 秒';
    var minutes = Math.floor(seconds / 60);
    var rest = Math.round(seconds % 60);
    return minutes + ' 分 ' + rest + ' 秒';
  }

  function setStatus(state, text) {
    statusBox.setAttribute('data-state', state);
    statusText.textContent = text;
  }

  /* ------------------------------------------------ 队列渲染 */

  function createItemNode(entry) {
    var li = document.createElement('li');
    li.className = 'item';
    li.setAttribute('data-status', 'waiting');

    var icon = document.createElement('span');
    icon.className = 'item-icon';
    icon.innerHTML = ICONS.waiting;

    var body = document.createElement('div');
    body.className = 'item-body';

    var top = document.createElement('div');
    top.className = 'item-top';

    var name = document.createElement('span');
    name.className = 'item-name';
    name.textContent = entry.name;
    name.title = entry.name;

    var status = document.createElement('span');
    status.className = 'item-status';
    status.textContent = '等待上传';

    top.appendChild(name);
    top.appendChild(status);

    var progress = document.createElement('div');
    progress.className = 'progress';
    var bar = document.createElement('div');
    bar.className = 'progress-bar';
    progress.appendChild(bar);

    var meta = document.createElement('div');
    meta.className = 'item-meta';
    var sizeInfo = document.createElement('span');
    sizeInfo.textContent = formatSize(entry.size);
    var extra = document.createElement('span');
    meta.appendChild(sizeInfo);
    meta.appendChild(extra);

    var error = document.createElement('div');
    error.className = 'item-error';
    error.hidden = true;

    body.appendChild(top);
    body.appendChild(progress);
    body.appendChild(meta);
    body.appendChild(error);

    li.appendChild(icon);
    li.appendChild(body);

    entry.node = {
      li: li,
      icon: icon,
      name: name,
      status: status,
      bar: bar,
      sizeInfo: sizeInfo,
      extra: extra,
      error: error
    };
    return li;
  }

  function setItemStatus(entry, state, text) {
    entry.status = state;
    entry.node.li.setAttribute('data-status', state);
    entry.node.icon.innerHTML = ICONS[state] || ICONS.waiting;
    entry.node.status.textContent = text;
  }

  function addFiles(fileList) {
    var files = Array.prototype.slice.call(fileList || []);
    if (!files.length) return;

    files.forEach(function (file) {
      var entry = {
        id: 'f' + (++sequence),
        file: file,
        name: file.name || '未命名文件',
        size: file.size || 0,
        uploaded: 0,
        status: 'waiting',
        speed: 0,
        lastLoaded: 0,
        lastTime: 0,
        node: null
      };
      queue.push(entry);
      queueList.appendChild(createItemNode(entry));
    });

    queueSection.hidden = false;
    updateSummary();
    pump();
  }

  function updateSummary() {
    var done = 0;
    var failed = 0;
    var totalBytes = 0;
    var doneBytes = 0;

    queue.forEach(function (entry) {
      totalBytes += entry.size;
      if (entry.status === 'completed') { done++; doneBytes += entry.size; }
      else if (entry.status === 'failed') { failed++; }
      else if (entry.status === 'uploading') { doneBytes += entry.uploaded; }
    });

    if (!queue.length) {
      queueSummary.textContent = '';
      return;
    }
    var parts = [];
    parts.push('共 ' + queue.length + ' 个文件');
    if (done) parts.push('完成 ' + done);
    if (failed) parts.push('失败 ' + failed);
    parts.push(formatSize(doneBytes) + ' / ' + formatSize(totalBytes));
    queueSummary.textContent = parts.join(' · ');
  }

  /* ------------------------------------------------ 上传流程 */

  function pump() {
    if (running) return;
    var next = null;
    for (var i = 0; i < queue.length; i++) {
      if (queue[i].status === 'waiting') { next = queue[i]; break; }
    }
    if (!next) {
      updateSummary();
      refreshPhotos();
      return;
    }
    running = true;
    uploadEntry(next).then(function () {
      running = false;
      pump();
    }, function () {
      running = false;
      pump();
    });
  }

  function uploadEntry(entry) {
    return new Promise(function (resolve) {
      setItemStatus(entry, 'uploading', '上传中 0%');
      entry.node.bar.style.width = '0%';
      entry.node.extra.textContent = '';
      entry.startedAt = Date.now();
      entry.lastTime = Date.now();
      entry.lastLoaded = 0;

      var form = new FormData();
      form.append('size', String(entry.size));
      form.append('file', entry.file, entry.name);

      var xhr = new XMLHttpRequest();
      xhr.open('POST', '/api/upload', true);
      xhr.timeout = 0;

      xhr.upload.onprogress = function (event) {
        if (!event.lengthComputable) return;
        applyProgress(entry, event.loaded, event.total);
      };

      xhr.onload = function () {
        var payload = null;
        try {
          payload = JSON.parse(xhr.responseText);
        } catch (e) {
          payload = null;
        }

        if (xhr.status === 403) {
          showGate('会话已失效，请重新输入 Token');
          fail(entry, '会话已失效，请重新输入 Token');
          return resolve();
        }
        if (payload && payload.success) {
          var savedName = payload.fileName || entry.name;
          complete(entry, savedName, payload.size);
          return resolve();
        }
        var message = payload && payload.message
          ? payload.message
          : '保存失败（HTTP ' + xhr.status + '）';
        fail(entry, message);
        resolve();
      };

      xhr.onerror = function () {
        fail(entry, '网络连接中断，请检查 Wi-Fi 与热点');
        resolve();
      };

      xhr.ontimeout = function () {
        fail(entry, '上传超时');
        resolve();
      };

      xhr.send(form);
    });
  }

  function applyProgress(entry, loaded, total) {
    entry.uploaded = loaded;
    if (total && total !== entry.size) entry.size = total;

    var percent = total > 0 ? Math.min(100, (loaded / total) * 100) : 0;
    entry.node.bar.style.width = percent.toFixed(1) + '%';
    setItemStatus(entry, 'uploading', '上传中 ' + percent.toFixed(0) + '%');

    var now = Date.now();
    var elapsed = (now - entry.lastTime) / 1000;
    if (elapsed >= 0.5) {
      var instant = (loaded - entry.lastLoaded) / elapsed;
      entry.speed = entry.speed > 0 ? entry.speed * 0.65 + instant * 0.35 : instant;
      entry.lastTime = now;
      entry.lastLoaded = loaded;
    }

    entry.node.sizeInfo.textContent = formatSize(loaded) + ' / ' + formatSize(total);

    var detail = [];
    if (entry.speed > 0) {
      detail.push(formatSpeed(entry.speed));
      var remain = entry.speed > 0 ? (total - loaded) / entry.speed : 0;
      if (remain > 0 && remain < 86400) detail.push('剩余 ' + formatDuration(remain));
    }
    entry.node.extra.textContent = detail.join(' · ');

    updateSummary();
  }

  function complete(entry, savedName, size) {
    entry.uploaded = entry.size;
    entry.node.bar.style.width = '100%';
    entry.node.sizeInfo.textContent = formatSize(size || entry.size);
    entry.node.extra.textContent = formatDuration((Date.now() - entry.startedAt) / 1000);
    entry.node.name.textContent = savedName;
    entry.node.name.title = savedName;
    entry.node.error.hidden = true;
    setItemStatus(entry, 'completed', '已完成');
    updateSummary();
  }

  function fail(entry, message) {
    entry.node.error.textContent = message;
    entry.node.error.hidden = false;
    entry.node.extra.textContent = '';
    setItemStatus(entry, 'failed', '失败');
    updateSummary();
  }

  /* ------------------------------------------------ 拖拽处理 */

  var dragDepth = 0;

  function hasFiles(event) {
    var dt = event.dataTransfer;
    if (!dt) return false;
    if (dt.types) {
      for (var i = 0; i < dt.types.length; i++) {
        if (dt.types[i] === 'Files') return true;
      }
    }
    return false;
  }

  function extractFiles(dataTransfer) {
    var result = [];
    var items = dataTransfer.items;
    if (items && items.length && typeof items[0].webkitGetAsEntry === 'function') {
      for (var i = 0; i < items.length; i++) {
        var item = items[i];
        if (item.kind !== 'file') continue;
        var entry = item.webkitGetAsEntry();
        if (entry && entry.isDirectory) continue;
        var file = item.getAsFile();
        if (file) result.push(file);
      }
      if (result.length) return result;
    }
    var files = dataTransfer.files;
    for (var j = 0; files && j < files.length; j++) result.push(files[j]);
    return result;
  }

  dropzone.addEventListener('dragenter', function (event) {
    event.preventDefault();
    if (!hasFiles(event)) return;
    dragDepth++;
    dropzone.classList.add('is-over');
  });

  dropzone.addEventListener('dragover', function (event) {
    event.preventDefault();
    if (!hasFiles(event)) return;
    event.dataTransfer.dropEffect = 'copy';
    dropzone.classList.add('is-over');
  });

  dropzone.addEventListener('dragleave', function (event) {
    event.preventDefault();
    dragDepth = Math.max(0, dragDepth - 1);
    if (dragDepth === 0) dropzone.classList.remove('is-over');
  });

  dropzone.addEventListener('drop', function (event) {
    event.preventDefault();
    dragDepth = 0;
    dropzone.classList.remove('is-over');
    addFiles(extractFiles(event.dataTransfer));
  });

  ['dragenter', 'dragover', 'drop'].forEach(function (name) {
    document.addEventListener(name, function (event) {
      if (!dropzone.contains(event.target)) event.preventDefault();
    });
  });

  /* ------------------------------------------------ 选择文件 */

  selectBtn.addEventListener('click', function (event) {
    event.stopPropagation();
    fileInput.click();
  });

  dropzone.addEventListener('click', function () {
    fileInput.click();
  });

  dropzone.addEventListener('keydown', function (event) {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      fileInput.click();
    }
  });

  fileInput.addEventListener('change', function () {
    addFiles(fileInput.files);
    fileInput.value = '';
  });

  clearBtn.addEventListener('click', function () {
    if (running) return;
    var kept = [];
    queue.forEach(function (entry) {
      if (entry.status === 'completed' || entry.status === 'failed') {
        if (entry.node && entry.node.li.parentNode) {
          entry.node.li.parentNode.removeChild(entry.node.li);
        }
      } else {
        kept.push(entry);
      }
    });
    queue = kept;
    if (!queue.length) queueSection.hidden = true;
    updateSummary();
  });

  /* ------------------------------------------------ 手机照片画廊 */

  var photoSignature = '';

  function createPhotoCard(photo) {
    var li = document.createElement('li');
    li.className = 'photo-card';
    if (photo.downloaded) li.setAttribute('data-sent', '1');

    var preview = document.createElement('div');
    preview.className = 'photo-preview';

    var img = document.createElement('img');
    img.loading = 'lazy';
    img.alt = photo.name;
    img.src = '/api/photos/' + encodeURIComponent(photo.id) + '/thumb';
    img.addEventListener('error', function () {
      img.hidden = true;
      preview.classList.add('no-preview');
    });
    preview.appendChild(img);

    var badge = document.createElement('span');
    badge.className = 'photo-badge';
    badge.innerHTML = ICONS.completed;
    badge.title = '已发送到电脑';
    preview.appendChild(badge);

    var meta = document.createElement('div');
    meta.className = 'photo-meta';
    var name = document.createElement('span');
    name.className = 'photo-name';
    name.textContent = photo.name;
    name.title = photo.name;
    var size = document.createElement('span');
    size.className = 'photo-size';
    size.textContent = formatSize(photo.size);
    meta.appendChild(name);
    meta.appendChild(size);

    var actions = document.createElement('div');
    actions.className = 'photo-actions';
    var link = document.createElement('a');
    link.className = 'btn btn-primary btn-small';
    link.href = '/api/photos/' + encodeURIComponent(photo.id);
    link.setAttribute('download', photo.name);
    link.textContent = '下载到电脑';
    actions.appendChild(link);

    li.appendChild(preview);
    li.appendChild(meta);
    li.appendChild(actions);
    return li;
  }

  function renderPhotos(list) {
    var signature = list.map(function (photo) {
      return photo.id + (photo.downloaded ? '1' : '0');
    }).join(',');

    if (signature === photoSignature) return;
    photoSignature = signature;

    photosSection.hidden = false;
    photosEmpty.hidden = list.length !== 0;
    photoGrid.innerHTML = '';

    var total = 0;
    list.forEach(function (photo) {
      total += photo.size || 0;
      photoGrid.appendChild(createPhotoCard(photo));
    });

    photosSummary.textContent = list.length
      ? list.length + ' 张 · ' + formatSize(total)
      : '';
  }

  function refreshPhotos() {
    if (running) return;
    fetch('/api/photos', { cache: 'no-store' })
      .then(function (response) {
        if (response.status === 403) {
          showGate();
          return null;
        }
        if (!response.ok) throw new Error(String(response.status));
        return response.json();
      })
      .then(function (data) {
        if (!data) return;
        renderPhotos((data && data.photos) || []);
      })
      .catch(function () {
        /* 手机不可达时保留上一次的列表 */
      });
  }

  photosRefresh.addEventListener('click', function () {
    photoSignature = '';
    refreshPhotos();
  });

  photosDownloadAll.addEventListener('click', function () {
    var links = photoGrid.querySelectorAll('a[download]');
    if (!links.length) return;
    var index = 0;
    (function next() {
      if (index >= links.length) return;
      links[index].click();
      index++;
      setTimeout(next, 600);
    })();
  });

  /* ------------------------------------------------ 状态轮询 */

  function refreshStatus() {
    if (running) return;
    fetch('/api/status', { cache: 'no-store' })
      .then(function (response) {
        if (response.status === 403) {
          showGate();
          return null;
        }
        if (!response.ok) throw new Error(String(response.status));
        return response.json();
      })
      .then(function (data) {
        if (!data) return;
        if (!authenticated) {
          hideGate();
        }
        var name = data.deviceName || '手机';
        setStatus('online', '已连接 ' + name + ' · 端口 ' + data.port);
        footerInfo.textContent = '文件将保存到 ' + name + ' 的 Download/LANTransfer 目录';
      })
      .catch(function () {
        setStatus('offline', '与手机的连接已断开');
      });
  }

  window.addEventListener('beforeunload', function (event) {
    if (running) {
      event.preventDefault();
      event.returnValue = '';
      return '';
    }
    return undefined;
  });

  refreshStatus();
  setInterval(refreshStatus, 8000);
  setInterval(refreshPhotos, 6000);
})();
