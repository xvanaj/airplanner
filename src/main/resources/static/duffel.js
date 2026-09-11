(function () {
    'use strict';
    const form = document.getElementById('duffelForm');
    const button = document.getElementById('duffelSubmit');
    const progress = document.getElementById('duffelProgress');
    if (!form || !button) return;
    const initiallyDisabled = button.disabled;
    form.addEventListener('submit', function () {
        button.disabled = true;
        progress.textContent = 'Searching airlines… This may take up to 25 seconds.';
    });
    window.addEventListener('pageshow', function () {
        button.disabled = initiallyDisabled;
        progress.textContent = '';
    });
})();
