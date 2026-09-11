/* Small conveniences for the search form: airport autocomplete, time presets,
   a weekend preset, and a "this may take a moment" hint while searching. */
(function () {
    'use strict';

    // ---- autocomplete: named groups first, then every airport -----------------
    const datalist = document.getElementById('airports');
    if (datalist) {
        fetch('/api/groups')
            .then(function (r) { return r.ok ? r.json() : []; })
            .then(function (groups) {
                groups.forEach(function (g) {
                    const option = document.createElement('option');
                    option.value = g.name;
                    option.label = g.description + ' (' + g.airports.length + ' airports)';
                    datalist.appendChild(option);
                });
            })
            .catch(function () { /* autocomplete is optional */ });

        fetch('/api/airports')
            .then(function (r) { return r.ok ? r.json() : []; })
            .then(function (airports) {
                const fragment = document.createDocumentFragment();
                airports.forEach(function (a) {
                    const option = document.createElement('option');
                    option.value = a.code;
                    option.label = a.name + ', ' + a.country;
                    fragment.appendChild(option);
                });
                datalist.appendChild(fragment);
            })
            .catch(function () { /* autocomplete is optional */ });
    }

    // ---- quick picks --------------------------------------------------------
    // The chips and the text field are two views of the same list: ticking a chip adds
    // its value, unticking removes it, and typing in the field re-ticks the chips.
    // Anything typed by hand that no chip covers is left alone.
    wirePicks('from', 'pick-origin');
    wirePicks('to', 'pick-destination');

    function wirePicks(fieldName, chipClass) {
        const field = document.querySelector('[name="' + fieldName + '"]');
        const chips = Array.prototype.slice.call(document.querySelectorAll('.' + chipClass));
        if (!field || chips.length === 0) { return; }
        const owned = chips.map(function (c) { return c.value.toLowerCase(); });

        function tokens() {
            return field.value.split(',')
                .map(function (s) { return s.trim(); })
                .filter(function (s) { return s.length > 0; });
        }

        function chipsFromField() {
            const current = tokens().map(function (s) { return s.toLowerCase(); });
            chips.forEach(function (chip) {
                chip.checked = current.indexOf(chip.value.toLowerCase()) >= 0;
            });
        }

        function fieldFromChips() {
            const typed = tokens().filter(function (t) { return owned.indexOf(t.toLowerCase()) < 0; });
            const picked = chips.filter(function (c) { return c.checked; })
                .map(function (c) { return c.value; });
            field.value = typed.concat(picked).join(', ');
        }

        chips.forEach(function (chip) { chip.addEventListener('change', fieldFromChips); });
        field.addEventListener('input', chipsFromField);
        chipsFromField();
    }

    // ---- time-of-day presets --------------------------------------------------
    document.querySelectorAll('.presets').forEach(function (group) {
        const leg = group.dataset.leg;
        const fromInput = document.querySelector('[name="' + leg + 'TimeFrom"]');
        const toInput = document.querySelector('[name="' + leg + 'TimeTo"]');
        group.querySelectorAll('button').forEach(function (button) {
            button.addEventListener('click', function () {
                if (fromInput) { fromInput.value = button.dataset.from; }
                if (toInput) { toInput.value = button.dataset.to; }
            });
        });
    });

    // ---- weekend preset -------------------------------------------------------
    const weekendButton = document.getElementById('weekendPreset');
    if (weekendButton) {
        weekendButton.addEventListener('click', function () {
            setDays('outDays', ['FRIDAY', 'SATURDAY']);
            setDays('backDays', ['SUNDAY', 'MONDAY']);
            setValue('minNights', 1);
            setValue('maxNights', 3);
            setValue('outTimeFrom', '15:00');
            setValue('outTimeTo', '23:59');
            setValue('backTimeFrom', '06:00');
            setValue('backTimeTo', '23:59');
        });
    }

    function setDays(name, wanted) {
        document.querySelectorAll('[name="' + name + '"]').forEach(function (box) {
            box.checked = wanted.indexOf(box.value) >= 0;
        });
    }

    function setValue(name, value) {
        const input = document.querySelector('[name="' + name + '"]');
        if (input) { input.value = value; }
    }

    // ---- return leg is meaningless for a one-way search ------------------------
    const oneWay = document.getElementById('oneWay');
    const returnLeg = document.getElementById('returnLeg');
    function syncReturnLeg() {
        if (!oneWay || !returnLeg) { return; }
        returnLeg.style.opacity = oneWay.checked ? '0.45' : '1';
        returnLeg.querySelectorAll('input, button').forEach(function (el) {
            el.disabled = oneWay.checked;
        });
    }
    if (oneWay) {
        oneWay.addEventListener('change', syncReturnLeg);
        syncReturnLeg();
    }

    // ---- searching takes a few seconds; say so --------------------------------
    const form = document.getElementById('searchForm');
    if (form) {
        // Store only named form fields, never result prices or API credentials.
        const storageKey = 'airplanner.savedSearches.v1';
        const savedSelect = document.getElementById('savedSearchSelect');
        const savedStatus = document.getElementById('savedSearchStatus');
        let saved = [];
        try {
            const parsed = JSON.parse(localStorage.getItem(storageKey) || '[]');
            if (Array.isArray(parsed)) {
                saved = parsed.filter(function (s) {
                    return s && typeof s.name === 'string' && typeof s.query === 'string';
                }).slice(0, 20);
            }
        } catch (e) { /* A blocked or corrupt store must not prevent searching. */ }

        function renderSaved() {
            savedSelect.length = 1;
            saved.forEach(function (entry, index) {
                const option = document.createElement('option');
                option.value = String(index);
                option.textContent = entry.name;
                savedSelect.appendChild(option);
            });
            syncSavedButtons();
        }
        function syncSavedButtons() {
            document.getElementById('loadSearch').disabled = savedSelect.value === '';
            document.getElementById('deleteSearch').disabled = savedSelect.value === '';
        }
        function persistSaved(next) {
            try {
                localStorage.setItem(storageKey, JSON.stringify(next));
                saved = next;
                renderSaved();
                return true;
            } catch (e) {
                savedStatus.textContent = 'Browser storage is unavailable. You can still bookmark the search URL.';
                return false;
            }
        }
        if (savedSelect) {
            document.getElementById('savedSearches').hidden = false;
            renderSaved();
            savedSelect.addEventListener('change', syncSavedButtons);
            document.getElementById('saveSearch').addEventListener('click', function () {
                if (!form.reportValidity()) { return; }
                const name = window.prompt('Name this search (reuse a name to replace it):');
                if (!name || !name.trim()) { return; }
                const trimmed = name.trim().slice(0, 100);
                const next = saved.filter(function (s) { return s.name !== trimmed; });
                next.unshift({name: trimmed, query: new URLSearchParams(new FormData(form)).toString()});
                if (persistSaved(next.slice(0, 20))) {
                    savedStatus.textContent = 'Search saved in this browser. Dates are saved exactly as entered. Up to 20 searches are kept.';
                }
            });
            document.getElementById('loadSearch').addEventListener('click', function () {
                const entry = saved[Number(savedSelect.value)];
                if (savedSelect.value === '' || !entry) { return; }
                const target = new URL(form.action, window.location.href);
                target.search = new URLSearchParams(entry.query).toString();
                window.location.assign(target.href);
            });
            document.getElementById('deleteSearch').addEventListener('click', function () {
                if (savedSelect.value === '') { return; }
                const selected = Number(savedSelect.value);
                if (persistSaved(saved.filter(function (s, index) { return index !== selected; }))) {
                    savedStatus.textContent = 'Saved search deleted.';
                }
            });
        }
        form.addEventListener('submit', function () {
            const spinner = document.getElementById('spinner');
            const button = document.getElementById('submitButton');
            if (spinner) { spinner.hidden = false; }
            if (button) { button.disabled = true; button.textContent = 'Searching...'; }
        });
        window.addEventListener('pageshow', function () {
            const spinner = document.getElementById('spinner');
            const button = document.getElementById('submitButton');
            if (spinner) { spinner.hidden = true; }
            if (button) { button.disabled = false; button.textContent = 'Search'; }
        });
    }
})();
