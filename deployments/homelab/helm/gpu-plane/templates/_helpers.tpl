{{/* Image reference: digest wins over tag (spec §8 freeze rule) */}}
{{- define "gpu-plane.image" -}}
{{- $ := index . 0 -}}{{- $img := index . 1 -}}
{{- if $img.digest -}}
{{- printf "%s/%s@%s" $.Values.registry $img.repo $img.digest -}}
{{- else -}}
{{- printf "%s/%s:%s" $.Values.registry $img.repo $img.tag -}}
{{- end -}}
{{- end -}}

{{- define "gpu-plane.pullSecrets" -}}
imagePullSecrets:
  - name: {{ .Values.imagePullSecret }}
{{- end -}}

{{- define "gpu-plane.hfOffline" -}}
- name: HF_HUB_OFFLINE
  value: "1"
- name: TRANSFORMERS_OFFLINE
  value: "1"
{{- end -}}
